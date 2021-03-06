package org.visola.photos.controllers;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Consts;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.visola.photos.dao.UserDao;
import org.visola.photos.security.UserAuthentication;
import org.visola.spring.security.tokenfilter.TokenService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class GoogleOAuthController {

  private static final String UTF8 = StandardCharsets.UTF_8.name();
  private static final String CSRF_TOKEN_COOKIE_NAME = "CSRFTOKEN";
  private static final String GOOGLE_OAUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/auth";
  private static final String GOOGLE_TOKEN_ENDPOINT = "https://www.googleapis.com/oauth2/v3/token";
  private static final String GOOGLE_EMAIL_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo";
  private static final String SCOPES = "email";
  private static final GrantedAuthority[] ROLES = new GrantedAuthority[]{new SimpleGrantedAuthority("ROLE_USER")};

  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final TokenService tokenService;
  private final UserDao userDao;

  @Autowired
  public GoogleOAuthController(HttpClient httpClient,
                               ObjectMapper objectMapper,
                               TokenService tokenService,
                               UserDao userDao,
                               @Value("${oauth.google.clientId}") String clientId,
                               @Value("${oauth.google.clientSecret}") String clientSecret,
                               @Value("${oauth.google.redirectUri}") String redirectUri) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.tokenService = tokenService;
    this.userDao = userDao;
  }

  @RequestMapping(method= RequestMethod.GET, value="/authenticate/google")
  public String redirectToGoogle(@RequestParam("redirectUrl") Optional<String> maybeRedirectUrl,
                                 HttpServletResponse response)  throws UnsupportedEncodingException {

    // Set CSRF token
    String csrfToken = UUID.randomUUID().toString();
    response.addCookie(createCsrfTokenCookie(csrfToken, (int) TimeUnit.MINUTES.toMillis(1)));

    String redirectUri = maybeRedirectUrl.orElse(this.redirectUri);

    StringBuffer uri = new StringBuffer("redirect:");
    uri.append(GOOGLE_OAUTH_ENDPOINT);
    uri.append("?response_type=code&scope=");
    uri.append(URLEncoder.encode(SCOPES, UTF8));
    uri.append("&client_id=");
    uri.append(URLEncoder.encode(clientId, UTF8));
    uri.append("&redirect_uri=");
    uri.append(URLEncoder.encode(redirectUri, UTF8));

    // In the state we send the CSRF token
    uri.append("&state=");
    uri.append(URLEncoder.encode(String.format("%s|%s", csrfToken, redirectUri), UTF8));

    return uri.toString();
  }

  @RequestMapping(method=RequestMethod.GET, value="/authenticate/oauth2callback")
  public ModelAndView receiveRedirect(String code,
                                      String state,
                                      HttpServletResponse response,
                                      @CookieValue(CSRF_TOKEN_COOKIE_NAME) String csrfToken) throws Exception {

    // Remove CSRF token
    response.addCookie(createCsrfTokenCookie(null, 0));

    String [] stateSplit = state.split("\\|");
    String stateCsrf = stateSplit[0];
    String redirectedUri = stateSplit[1];

    // State stores CSRF token
    if (!csrfToken.equals(stateCsrf)) {
      throw new AccessDeniedException("Invalid CSRF token.");
    }

    String email = getUserEmail(getToken(code, redirectedUri));
    org.visola.photos.model.User user = null;
    Optional<org.visola.photos.model.User> maybeUser = userDao.findByEmail(email);
    if (maybeUser.isPresent()) {
      user = maybeUser.get();
    } else {
      user = new org.visola.photos.model.User();
      user.setEmail(email);
      user.setId(userDao.create(user));
    }

    String token = tokenService.generateToken(new UserAuthentication(user));

    ModelAndView mv = new ModelAndView("oauth2callback");
    mv.addObject("user", user);
    mv.addObject("token", token);
    return mv;
  }

  private String getUserEmail(String token) throws Exception {
    HttpGet get = new HttpGet(GOOGLE_EMAIL_ENDPOINT);
    get.addHeader("Authorization", String.format("Bearer %s", token));

    HttpResponse response = httpClient.execute(get);
    JsonNode node = objectMapper.readTree(response.getEntity().getContent());
    return node.get("email").asText();
  }

  private String getToken(String code, String redirectUri) throws Exception {
    List<NameValuePair> formParams = new ArrayList<>();
    formParams.add(new BasicNameValuePair("code", code));
    formParams.add(new BasicNameValuePair("client_id", clientId));
    formParams.add(new BasicNameValuePair("client_secret", clientSecret));
    formParams.add(new BasicNameValuePair("redirect_uri", redirectUri));
    formParams.add(new BasicNameValuePair("grant_type", "authorization_code"));

    HttpPost post = new HttpPost(GOOGLE_TOKEN_ENDPOINT);
    post.setEntity(new UrlEncodedFormEntity(formParams, Consts.UTF_8));
    HttpResponse response = httpClient.execute(post);

    if (response.getStatusLine().getStatusCode() >= 200 && response.getStatusLine().getStatusCode() < 300) {
      JsonNode node = objectMapper.readTree(response.getEntity().getContent());
      return node.get("access_token").asText();
    } else {
      throw new RuntimeException(String.format("Error while fetching token from Google. Status: %d, Response: %s", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()));
    }
  }

  private Cookie createCsrfTokenCookie(String csrfToken, int age) {
    Cookie cookie = new Cookie(CSRF_TOKEN_COOKIE_NAME, csrfToken);
    cookie.setMaxAge(age);
    return cookie;
  }

}
