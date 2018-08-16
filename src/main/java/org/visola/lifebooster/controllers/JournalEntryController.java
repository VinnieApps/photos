package org.visola.lifebooster.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.visola.lifebooster.dao.JournalEntryDao;
import org.visola.lifebooster.model.JournalEntry;

@RequestMapping("${api.base.path}/journal-entries")
@RestController
public class JournalEntryController {

  private final JournalEntryDao journalEntryDao;

  public JournalEntryController(JournalEntryDao journalEntryDao) {
    this.journalEntryDao = journalEntryDao;
  }

  @GetMapping
  public List<JournalEntry> getEntries() {
    return journalEntryDao.list();
  }

}