/* LanguageTool, a natural language style checker
 * Copyright (C) 2011 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.openoffice;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.gui.Configuration;
import org.languagetool.gui.Tools;

import com.sun.star.beans.PropertyState;
import com.sun.star.beans.PropertyValue;
import com.sun.star.lang.Locale;
import com.sun.star.lang.XComponent;
import com.sun.star.linguistic2.ProofreadingResult;
import com.sun.star.linguistic2.SingleProofreadingError;
import com.sun.star.text.TextMarkupType;
import com.sun.star.text.XFlatParagraph;
import com.sun.star.text.XMarkingAccess;
import com.sun.star.text.XParagraphCursor;
import com.sun.star.text.XTextCursor;
import com.sun.star.text.XTextRange;
import com.sun.star.text.XTextViewCursor;
import com.sun.star.text.XWordCursor;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;

/**
 * Class defines the spell and grammar check dialog
 * @since 5.1
 * @author Fred Kruse
 */
public class SpellAndGrammarCheckDialog extends Thread {
  
  private static boolean debugMode = OfficeTools.DEBUG_MODE_CD;         //  should be false except for testing

  private final ResourceBundle messages = JLanguageTool.getMessageBundle();
  private static final String spellingError = "Spelling Error";
  private static final String spellRuleId = "SpellingError";
  private static int nLastFlat = 0;
  private XComponentContext xContext;
  private MultiDocumentsHandler documents;
  private SwJLanguageTool langTool;
  private Language lastLanguage;
  private ExtensionSpellChecker spellChecker;
  private Locale locale;
  private int checkType = 0;
  private DocumentCache docCache;
  private boolean doInit = true;
  
  SpellAndGrammarCheckDialog(XComponentContext xContext, MultiDocumentsHandler documents, Language language) {
    debugMode = OfficeTools.DEBUG_MODE_CD;
    this.xContext = xContext;
    this.documents = documents;
    spellChecker = new ExtensionSpellChecker();
    lastLanguage = language;
    locale = LinguisticServices.getLocale(language);
    setLangTool(documents, language);
  }

  /**
   * Initialize LanguageTool to run in LT check dialog and next error function
   */
  private void setLangTool(MultiDocumentsHandler documents, Language language) {
    langTool = documents.initLanguageTool(language, false);
    documents.initCheck(langTool, LinguisticServices.getLocale(language));
    if (debugMode) {
      for (String id : langTool.getDisabledRules()) {
        MessageHandler.printToLogFile("After init disabled rule: " + id);
      }
    }
    doInit = false;
  }

  /**
   * opens the LT check dialog for spell and grammar check
   */
  @Override
  public void run() {
    if (!documents.javaVersionOkay()) {
      MessageHandler.printToLogFile("Wrong Java Version Check Dialog not started");
      return;
    }
    try {
//      documents.setLtDialogIsRunning(true);
      LtCheckDialog checkDialog = new LtCheckDialog(xContext);
      checkDialog.show();
    } catch (Throwable e) {
      MessageHandler.showError(e);
    }
  }

  /**
   * Update the cache of the current document 
   */
  private DocumentCache updateDocumentCache(int nPara, XComponent xComponent, DocumentCursorTools docCursor, SingleDocument document) {
    DocumentCache docCache = document.getUpdatedDocumentCache(nPara);
    if (docCache == null) {
      FlatParagraphTools flatPara = document.getFlatParagraphTools();
      if (flatPara == null) {
        flatPara = new FlatParagraphTools(xComponent);
      } else {
        flatPara.init();
      }
      Configuration config = documents.getConfiguration();
      docCache = new DocumentCache(docCursor, flatPara, -1, config == null ? null : LinguisticServices.getLocale(config.getDefaultLanguage()));
    }
    return docCache;
  }

  /**
   * Get the current document
   * Wait until it is initialized (by LO/OO)
   */
  private SingleDocument getCurrentDocument() {
    SingleDocument currentDocument = documents.getCurrentDocument();
    while (currentDocument == null) {
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        MessageHandler.printException(e);
      }
      currentDocument = documents.getCurrentDocument();
    }
    return currentDocument;
  }

   /**
   * Find the next error relative to the position of cursor and set the view cursor to the position
   */
  public void nextError() {
    SingleDocument document = getCurrentDocument();
    XComponent xComponent = document.getXComponent();
    DocumentCursorTools docCursor = new DocumentCursorTools(xComponent);
    docCache = updateDocumentCache(nLastFlat,xComponent, docCursor, document);
    if (docCache == null || docCache.size() <= 0) {
      return;
    }
    ViewCursorTools viewCursor = new ViewCursorTools(xContext);
    int yFlat = getCurrentFlatParagraphNumber(viewCursor, docCache);
    int x = viewCursor.getViewCursorCharacter();
    while (yFlat < docCache.size()) {
      CheckError nextError = getNextErrorInParagraph (x, yFlat, document, docCursor, null);
      if (nextError != null && setFlatViewCursor(nextError.error.nErrorStart + 1, yFlat, viewCursor, docCache, docCursor)) {
        return;
      }
      x = 0;
      yFlat++;
    }
    MessageHandler.showMessage(messages.getString("guiCheckComplete"), false);
  }

  /**
   * get the current number of the flat paragraph related to the position of view cursor
   * the function considers footnotes, headlines, tables, etc. included in the document 
   */
  private int getCurrentFlatParagraphNumber(ViewCursorTools viewCursor, DocumentCache docCache) {
    int y = viewCursor.getViewCursorParagraph();
    String paraText = viewCursor.getViewCursorParagraphText();
    if (y == 0 && !paraText.equals(docCache.getTextParagraph(y))) {
      for (int i = nLastFlat; i < docCache.size(); i++) {
        if (docCache.getNumberOfTextParagraph(i) < 0 && paraText.equals(docCache.getFlatParagraph(i))) {
          nLastFlat = i;
          return nLastFlat; 
        }
      }
      for (int i = 0; i < nLastFlat; i++) {
        if (docCache.getNumberOfTextParagraph(i) < 0 && paraText.equals(docCache.getFlatParagraph(i))) {
          nLastFlat = i;
          return nLastFlat; 
        }
      }
    } else if (y >= docCache.size()) {
      nLastFlat = docCache.size() - 1;
      return nLastFlat; 
    }
    nLastFlat = docCache.getFlatParagraphNumber(y);
    return nLastFlat; 
  }

  /**
   * Moves the viewCursor nChars character to the right (if nChars > 0) or to the left (if nChars < 0) 
   */
  private boolean moveViewCursor(long nChars, ViewCursorTools viewCursor)  {
    if (nChars == 0) {
      return true;
    }
    XTextViewCursor vCursor = viewCursor.getViewCursor();
    vCursor.collapseToStart();
    boolean toRight = true;
    if (nChars < 0) {
      toRight = false;
      nChars = -nChars;
    }
    while (nChars > Short.MAX_VALUE) {
      if (toRight) {
        vCursor.goRight(Short.MAX_VALUE, false);
      } else {
        vCursor.goLeft(Short.MAX_VALUE, false);
      }
      nChars -= Short.MAX_VALUE;
    }
    if (toRight) {
      return vCursor.goRight((short)nChars, false);
    } else {
      return vCursor.goLeft((short)nChars, false);
    }
  }

  /**
   * Set the view cursor to text position x, y 
   * y = Paragraph of pure text (no footnotes, tables, etc.)
   * x = number of character in paragraph
   */
  public void setTextViewCursor(int x, int y, ViewCursorTools viewCursor, DocumentCursorTools docCursor)  {
    try {
      XTextViewCursor vCursor = viewCursor.getViewCursor();
      if (vCursor != null) {
        XParagraphCursor pCursor = docCursor.getParagraphCursor();
        if (pCursor != null) {
          pCursor.gotoStart(false);
          for (int i = 0; i < y && pCursor.gotoNextParagraph(false); i++) {
          }
          pCursor.gotoStartOfParagraph(false);
          vCursor.gotoRange(pCursor.getStart(), false);
          vCursor.goRight((short)x, false);
        }
      }
    } catch (Throwable t) {
      MessageHandler.printException(t);
    }
  }

  /**
   * Set the view cursor to position of flat paragraph xFlat, yFlat 
   * y = Flat paragraph of pure text (includes footnotes, tables, etc.)
   * x = number of character in flat paragraph
   */
  private boolean setFlatViewCursor(int xFlat, int yFlat, ViewCursorTools viewCursor, DocumentCache docCache, DocumentCursorTools docCursor)  {
    int y = docCache.getNumberOfTextParagraph(yFlat);
    if (y >= 0) {
      setTextViewCursor(xFlat, y, viewCursor, docCursor);
      return true;
    }
    long nChar = 0;
    if (yFlat < docCache.getFlatParagraphNumber(docCache.textSize() - 1)) {
      for (int yTmp = yFlat + 1; y < 0; yTmp++) {
        nChar -= docCache.getFlatParagraph(yTmp - 1).length() + 1;
        y = docCache.getNumberOfTextParagraph(yTmp);
      }
      nChar += xFlat;
      setTextViewCursor(0, y, viewCursor, docCursor);
      return moveViewCursor(nChar, viewCursor);
    } else {
      for (int yTmp = yFlat - 1; y < 0; yTmp--) {
        nChar += docCache.getFlatParagraph(yTmp).length() + 1;
        y = docCache.getNumberOfTextParagraph(yTmp);
      }
      nChar += xFlat;
      setTextViewCursor(0, y, viewCursor, docCursor);
      return moveViewCursor(nChar, viewCursor);
    }
  }

  /**
   * Get the first error in the flat paragraph nFPara at or after character position x
   */
  private CheckError getNextErrorInParagraph (int x, int nFPara, SingleDocument document, 
      DocumentCursorTools docTools, Map<Integer, Set<Integer>> ignoredSpellMatches) {
    String text = docCache.getFlatParagraph(nFPara);
    locale = docCache.getFlatParagraphLocale(nFPara);
    if (locale.Language.equals("zxx")) { // unknown Language 
      locale = documents.getLocale();
    }
//    MessageHandler.printToLogFile("getNextErrorInParagraph: locale = " + locale.Language + "-" + locale.Country + "-" + locale.Variant);
    int[] footnotePosition = docCache.getFlatParagraphFootnotes(nFPara);

    CheckError sError = null;
    SingleProofreadingError gError = null;
    if (checkType != 2) {
      sError = getNextSpellErrorInParagraph (x, nFPara, text, locale, ignoredSpellMatches);
    }
    if (checkType != 1) {
      gError = getNextGrammatikErrorInParagraph(x, nFPara, text, footnotePosition, locale, document);
    }
    if (sError != null) {
      if (gError != null && gError.nErrorStart < sError.error.nErrorStart) {
        return new CheckError(locale, gError);
      }
      return sError; 
    } else if (gError != null) {
      return new CheckError(locale, gError);
    } else {
      return null;
    }
  }
  
  /**
   * Get the first spelling error in the flat paragraph nPara at or after character position x
   */
  private CheckError getNextSpellErrorInParagraph (int x, int nPara, String text, Locale locale, 
      Map<Integer, Set<Integer>> ignoredSpellMatches) {
    List<CheckError> spellErrors = spellChecker.getSpellErrors(nPara, text, locale, ignoredSpellMatches);
    if (spellErrors != null) {
      for (CheckError spellError : spellErrors) {
        if (spellError.error != null && spellError.error.nErrorStart >= x) {
          if (debugMode) {
            MessageHandler.printToLogFile("Next Error: ErrorStart == " + spellError.error.nErrorStart + ", x: " + x);
          }
          return spellError;
        }
      }
    }
    return null;
  }
  
  /**
   * Get the first grammatical error in the flat paragraph y at or after character position x
   */
  SingleProofreadingError getNextGrammatikErrorInParagraph(int x, int nFPara, String text, int[] footnotePosition, Locale locale, SingleDocument document) {
    if (text == null || text.isEmpty() || x >= text.length() || !documents.hasLocale(locale)) {
      return null;
    }
    PropertyValue[] propertyValues = { new PropertyValue("FootnotePositions", -1, footnotePosition, PropertyState.DIRECT_VALUE) };
    ProofreadingResult paRes = new ProofreadingResult();
    paRes.nStartOfSentencePosition = 0;
    paRes.nStartOfNextSentencePosition = 0;
    paRes.nBehindEndOfSentencePosition = paRes.nStartOfNextSentencePosition;
    paRes.xProofreader = null;
    paRes.aLocale = locale;
    paRes.aDocumentIdentifier = document.getDocID();
    paRes.aText = text;
    paRes.aProperties = propertyValues;
    paRes.aErrors = null;
    Language langForShortName = documents.getLanguage(locale);
    if (doInit || !langForShortName.equals(lastLanguage)) {
      lastLanguage = langForShortName;
      setLangTool(documents, lastLanguage);
      document.removeResultCache(nFPara);
    }
    while (paRes.nStartOfNextSentencePosition < text.length()) {
      paRes.nStartOfSentencePosition = paRes.nStartOfNextSentencePosition;
      paRes.nStartOfNextSentencePosition = text.length();
      paRes.nBehindEndOfSentencePosition = paRes.nStartOfNextSentencePosition;
      if (debugMode) {
        for (String id : langTool.getDisabledRules()) {
          MessageHandler.printToLogFile("Dialog disabled rule: " + id);
        }
      }
      paRes = document.getCheckResults(text, locale, paRes, propertyValues, false, langTool, nFPara);
      if (paRes.aErrors != null) {
        if (debugMode) {
          MessageHandler.printToLogFile("Number of Errors = " + paRes.aErrors.length);
        }
        for (SingleProofreadingError error : paRes.aErrors) {
          if (debugMode) {
            MessageHandler.printToLogFile("Start: " + error.nErrorStart + ", ID: " + error.aRuleIdentifier);
          }
          if (error.nErrorStart >= x) {
            return error;
          }        
        }
      }
    }
    return null;
  }
  
  /** 
   * Class for spell checking in LT check dialog
   * The LO/OO spell checker is used
   */
  public class ExtensionSpellChecker {

    private LinguisticServices linguServices;
     
    ExtensionSpellChecker() {
      linguServices = new LinguisticServices(xContext);
    }

    /**
     * get a list of all spelling errors of the flat paragraph nPara
     */
    public List<CheckError> getSpellErrors(int nPara, String text, Locale lang, Map<Integer, Set<Integer>> ignoredSpellMatches) {
      try {
        List<CheckError> errorArray = new ArrayList<CheckError>();
        XFlatParagraph xFlatPara = getCurrentDocument().getFlatParagraphTools().getFlatParagraphAt(nPara);
        Locale locale = null;
        AnalyzedSentence analyzedSentence = langTool.getAnalyzedSentence(text);
        AnalyzedTokenReadings[] tokens = analyzedSentence.getTokensWithoutWhitespace();
        for (int i = 0; i < tokens.length; i++) {
          AnalyzedTokenReadings token = tokens[i];
          String sToken = token.getToken();
          if (!token.isNonWord() && sToken.length() > 1) {
            if (i < tokens.length - 1 && tokens[i + 1].getToken().equals(".")) {
              sToken += ".";
            }
            if (xFlatPara != null) {
              locale = xFlatPara.getLanguageOfText(token.getStartPos(), token.getEndPos() - token.getStartPos());
            }
            if (locale == null) {
              locale = lang;
            }
            if (!linguServices.isCorrectSpell(sToken, locale)) {
              SingleProofreadingError aError = new SingleProofreadingError();
              if (debugMode) {
                MessageHandler.printToLogFile("Error: Word: " + sToken 
                    + ", Start: " + token.getStartPos() + ", End: " + token.getEndPos());
              }
              if (!isIgnoredMatch (token.getStartPos(), token.getEndPos(), nPara, ignoredSpellMatches)) {
                aError.nErrorType = TextMarkupType.SPELLCHECK;
                aError.aFullComment = JLanguageTool.getMessageBundle().getString("desc_spelling");
                aError.aShortComment = aError.aFullComment;
                aError.nErrorStart = token.getStartPos();
                aError.nErrorLength = token.getEndPos() - token.getStartPos();
                aError.aRuleIdentifier = spellRuleId;
                errorArray.add(new CheckError(locale, aError));
                String[] alternatives = linguServices.getSpellAlternatives(token.getToken(), locale);
                if (alternatives != null) {
                  aError.aSuggestions = alternatives;
                } else {
                  aError.aSuggestions = new String[0];
                }
              }
            }
          }
        }
        return errorArray;
      } catch (Throwable t) {
        MessageHandler.showError(t);
      }
      return null;
    }

    /**
     * get a list of all spelling errors of the (pure) text paragraph numPara
     */
    public List<CheckError> getSpellErrors(int numPara, Locale lang, 
        DocumentCursorTools cursorTools, Map<Integer, Set<Integer>> ignoredSpellMatches) {
      try {
        List<CheckError> errorArray = new ArrayList<CheckError>();
        XFlatParagraph xFlatPara = getCurrentDocument().getFlatParagraphTools().getFlatParagraphAt(docCache.getFlatParagraphNumber(numPara));
        WordsFromParagraph wParas = new WordsFromParagraph(numPara, cursorTools);
        String word = wParas.getNextWord();
        while (word != null) {
          int wordBegin = wParas.getBeginOfWord();
          int wordLength = wParas.getLengthOfWord();
          if (xFlatPara != null) {
            locale = xFlatPara.getLanguageOfText(wordBegin, wordLength);
          }
          if (locale == null) {
            locale = lang;
          }
          if (!linguServices.isCorrectSpell(word, locale)) {
            if (word.charAt(wordLength - 1) == '.') {
              word = word.substring(0, wordLength - 1);
              wordLength--;
            }
            if (!isIgnoredMatch (wordBegin, wordBegin + wordLength, docCache.getFlatParagraphNumber(numPara), ignoredSpellMatches)) {
              SingleProofreadingError aError = new SingleProofreadingError();
              aError.nErrorType = TextMarkupType.SPELLCHECK;
              aError.aFullComment = spellingError;
              aError.aShortComment = aError.aFullComment;
              aError.nErrorStart = wordBegin;
              aError.nErrorLength = wordLength;
              aError.aRuleIdentifier = spellRuleId;
              String[] alternatives = linguServices.getSpellAlternatives(word, locale);
              if (alternatives != null) {
                aError.aSuggestions = alternatives;
              } else {
                aError.aSuggestions = new String[0];
              }
              errorArray.add(new CheckError(locale, aError));
            }
          }
          word = wParas.getNextWord();
        }
        return errorArray;
      } catch (Throwable t) {
        MessageHandler.showError(t);
      }
      return null;
    }

    /**
     * Test if the word on the given position should be ignored (matches the ignore once set)
     */
    boolean isIgnoredMatch (int wBegin, int wEnd, int nPara, Map<Integer, Set<Integer>> ignoredSpellMatches) {
      if (ignoredSpellMatches != null && ignoredSpellMatches.containsKey(nPara)) {
        for (int nChar : ignoredSpellMatches.get(nPara)) {
          if (wBegin <= nChar && wEnd > nChar) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * replaces all words that matches 'word' with the string 'replace'
     * gives back a map of positions where a replace was done (for undo function)
     */
    public Map<Integer, List<Integer>> replaceAllWordsInText(String word, String replace, 
        DocumentCursorTools cursorTools, FlatParagraphTools flatPara) {
      if (word == null || replace == null || word.isEmpty() || replace.isEmpty() || word.equals(replace)) {
        return null;
      }
      Map<Integer, List<Integer>> replacePoints = new HashMap<Integer, List<Integer>>();
      try {
        for (int n = 0; n < docCache.size(); n++) {
          if (docCache.getNumberOfTextParagraph(n) < 0) {
            AnalyzedSentence analyzedSentence = langTool.getAnalyzedSentence(docCache.getFlatParagraph(n));
            AnalyzedTokenReadings[] tokens = analyzedSentence.getTokensWithoutWhitespace();
            for (int i = tokens.length - 1; i >= 0 ; i--) {
              List<Integer> x ;
              if (tokens[i].getToken().equals(word)) {
                flatPara.changeTextOfParagraph(n, tokens[i].getStartPos(), word.length(), replace);
                if (replacePoints.containsKey(n)) {
                  x = replacePoints.get(n);
                } else {
                  x = new ArrayList<Integer>();
                }
                x.add(0, tokens[i].getStartPos());
                replacePoints.put(n, x);
                if (debugMode) {
                  MessageHandler.printToLogFile("add change undo: y = " + n + ", NumX = " + replacePoints.get(n).size());
                }
              }
            }
          }
        }
      } catch (Throwable t) {
        MessageHandler.showError(t);
      }
      WordsFromParagraph wParas = new WordsFromParagraph(0, cursorTools);
      Map<Integer, List<Integer>> docReplaces = wParas.replaceWordInText(word, replace);
      for (int n : docReplaces.keySet()) {
        replacePoints.put(docCache.getFlatParagraphNumber(n), docReplaces.get(n));
      }
      return replacePoints;
    }

    /**
     * class to extract and manipulate words of a paragraph
     */
    class WordsFromParagraph {
      int paraLength;
      int wordStart = -1;
      int wordLength;
      String word;
      XParagraphCursor pCursor;
      XWordCursor wCursor;
      
      public WordsFromParagraph(int n, DocumentCursorTools cursorTools) {
        pCursor = cursorTools.getParagraphCursor();
        wCursor = UnoRuntime.queryInterface(XWordCursor.class, pCursor);
        pCursor.gotoStart(false);
        for (int i = 0; i < n && pCursor != null; i++) {
          pCursor.gotoNextParagraph(false);
        }
        pCursor.gotoStartOfParagraph(false);
        pCursor.gotoEndOfParagraph(true);
        paraLength = pCursor.getString().length();
        pCursor.gotoStartOfParagraph(false);
        wCursor.gotoStartOfWord(false);
      }

      /**
       * get the length of the paragraph
       */
      public int getLengthOfParagraph() {
        return paraLength;
      }
      
      /**
       * get the next word of the paragraph
       */
      public String getNextWord() {
        if (wordStart >= 0) {
          boolean res = wCursor.gotoNextWord(false);
          if (!res) {
            return null;
          }
        } else {
          wCursor.gotoEndOfWord(false);
        }
        wCursor.gotoStartOfWord(false);
        wCursor.gotoEndOfWord(true);
        String tmpWord = wCursor.getString();
        wCursor.gotoStartOfWord(false);
        XTextRange startOfWord = wCursor.getStart();
        pCursor.gotoStartOfParagraph(true);
        int nStart = pCursor.getString().length();
        pCursor.gotoRange(startOfWord, false);
        if (tmpWord.isEmpty() || nStart < wordStart + wordLength 
            || wordStart + wordLength + tmpWord.length() > paraLength) {
          return null;
        }
        word = tmpWord;
        wordStart = nStart;
        wordLength = word.length();
        return word;
      }

      /**
       * get the begin of the word that was returned from getNextWord
       */
      public int getBeginOfWord () {
        return wordStart;
      }

      /**
       * get the length of the word that was returned from getNextWord
       */
      public int getLengthOfWord () {
        return wordLength;
      }

      /**
       * replace all words that matches "oWord" of document (only pure text without footnotes, tables, etc.)
       * by the string "replace"
       * gives back a map of positions where a replace was done (for undo function)
       */
      public Map<Integer, List<Integer>> replaceWordInText (String oWord, String replace) {
        Map<Integer, List<Integer>> paraMap = null;
        pCursor.gotoStart(false);
        boolean pRes = true;
        int nPara = 0;
        while (pRes) {
          pCursor.gotoStartOfParagraph(false);
          pCursor.gotoEndOfParagraph(true);
          String paraText = pCursor.getString();
          paraLength = paraText.length();
          if (debugMode) {
            MessageHandler.printToLogFile("Paragraph (" + paraLength + "):" + pCursor.getString());
          }
          pCursor.gotoStartOfParagraph(false);
          wordStart = 0;
          wordLength = 0;
          boolean wRes = wCursor.gotoEndOfWord(false);
          while (wRes) {
            wCursor.gotoStartOfWord(false);
            wCursor.gotoEndOfWord(true);
            String tmpWord = wCursor.getString();
            boolean addToUndo = false;
            if (tmpWord.endsWith(".")) {
              wCursor.goLeft((short)1, false);
              wCursor.gotoStartOfWord(true);
              tmpWord = wCursor.getString();
            }
            if (oWord.equals(tmpWord)) {
              wCursor.setString(replace);
              tmpWord = replace;
              addToUndo = true;
            }
            wCursor.gotoStartOfWord(false);
            XTextRange startOfWord = wCursor.getStart();
            pCursor.gotoStartOfParagraph(true);
            int nStart = pCursor.getString().length();
            if (addToUndo) {
              paraMap = addChangeToUndoMap(nStart, nPara, paraMap);
            }
            pCursor.gotoRange(startOfWord, false);
            if (tmpWord.isEmpty() || nStart < wordStart + wordLength 
                || wordStart + wordLength + tmpWord.length() > paraLength) {
              break;
            }
            if (debugMode) {
              MessageHandler.printToLogFile(tmpWord);
            }
            word = tmpWord;
            wordStart = nStart;
            wordLength = word.length();
            wRes = wCursor.gotoNextWord(false);
          }
          nPara++;
          pRes = pCursor.gotoNextParagraph(false);
        }
        return paraMap;
      }

      /**
       * add a replace position to the undo map
       * gives back the map
       */
      private Map<Integer, List<Integer>> addChangeToUndoMap(int x, int y, Map<Integer, List<Integer>> paraMap) {
        if (paraMap == null) {
          paraMap = new HashMap<Integer, List<Integer>>();
        }
        List<Integer> xVals;
        if (paraMap.containsKey(y)) {
          xVals = paraMap.get(y);
        } else {
          xVals = new ArrayList<Integer>();
        }
        xVals.add(x);
        paraMap.put(y, xVals);
        return paraMap;
      }

    }
    
  }

  /**
   * class to store the information for undo
   */
  public class UndoContainer {
    public int x;
    public int y;
    public String action;
    public String ruleId;
    public String word;
    public Map<Integer, List<Integer>> orgParas;
    
    UndoContainer(int x, int y, String action, String ruleId, String word, Map<Integer, List<Integer>> orgParas) {
      this.x = x;
      this.y = y;
      this.action = action;
      this.ruleId = ruleId;
      this.orgParas = orgParas;
      this.word = word;
    }
  }

  /**
   * class contains the SingleProofreadingError and the locale of the match
   */
  public class CheckError {
    public Locale locale;
    public SingleProofreadingError error;
    
    CheckError(Locale locale, SingleProofreadingError error) {
      this.locale = locale;
      this.error = error;
    }
  }
  
  /**
   * Class for dialog to check text for spell and grammar errors
   */
  public class LtCheckDialog implements ActionListener {
    private final static int maxUndos = 20;
    private final static int toolTipWidth = 300;
    private final String dialogName = messages.getString("guiOOoCheckDialogName");
    private final String labelLanguage = messages.getString("textLanguage");
    private final String labelSuggestions = messages.getString("guiOOosuggestions"); 
    private final String moreButtonName = messages.getString("guiMore"); 
    private final String ignoreButtonName = messages.getString("guiOOoIgnoreButton"); 
    private final String ignoreAllButtonName = messages.getString("guiOOoIgnoreAllButton"); 
    private final String deactivateRuleButtonName = messages.getString("loContextMenuDeactivateRule"); 
    private final String addToDictionaryName = messages.getString("guiOOoaddToDictionary");
    private final String changeButtonName = messages.getString("guiOOoChangeButton"); 
    private final String changeAllButtonName = messages.getString("guiOOoChangeAllButton"); 
    private final String helpButtonName = messages.getString("guiMenuHelp"); 
    private final String optionsButtonName = messages.getString("guiOOoOptionsButton"); 
    private final String undoButtonName = messages.getString("guiUndo");
    private final String closeButtonName = messages.getString("guiCloseButton");
    private final String changeLanguageList[] = { messages.getString("guiOOoChangeLanguageRequest"),
                                                  messages.getString("guiOOoChangeLanguageMatch"),
                                                  messages.getString("guiOOoChangeLanguageParagraph") };
    private final String languageHelp = messages.getString("loDialogLanguageHelp");
    private final String changeLanguageHelp = messages.getString("loDialogChangeLanguageHelp");
    private final String matchDescriptionHelp = messages.getString("loDialogMatchDescriptionHelp");
    private final String matchParagraphHelp = messages.getString("loDialogMatchParagraphHelp");
    private final String suggestionsHelp = messages.getString("loDialogSuggestionsHelp");
    private final String checkTypeHelp = messages.getString("loDialogCheckTypeHelp");
    private final String helpButtonHelp = messages.getString("loDialogHelpButtonHelp"); 
    private final String optionsButtonHelp = messages.getString("loDialogOptionsButtonHelp"); 
    private final String undoButtonHelp = messages.getString("loDialogUndoButtonHelp");
    private final String closeButtonHelp = messages.getString("loDialogCloseButtonHelp");
    private final String moreButtonHelp = messages.getString("loDialogMoreButtonHelp"); 
    private final String ignoreButtonHelp = messages.getString("loDialogIgnoreButtonHelp"); 
    private final String ignoreAllButtonHelp = messages.getString("loDialogIgnoreAllButtonHelp"); 
    private final String deactivateRuleButtonHelp = messages.getString("loDialogDeactivateRuleButtonHelp"); 
    private final String addToDictionaryHelp = messages.getString("loDialogAddToDictionaryButtonHelp");
    private final String changeButtonHelp = messages.getString("loDialogChangeButtonHelp"); 
    private final String changeAllButtonHelp = messages.getString("loDialogChangeAllButtonHelp"); 
    private JDialog dialog;
    private JLabel languageLabel;
    private JComboBox<String> language;
    private JComboBox<String> changeLanguage; 
    private JTextArea errorDescription;
    private JTextPane sentenceIncludeError;
    private JLabel suggestionsLabel;
    private JList<String> suggestions;
    private JLabel checkTypeLabel;
    private ButtonGroup checkTypeGroup;
    private JRadioButton[] checkTypeButtons;
    private JButton more; 
    private JButton ignoreOnce; 
    private JButton ignoreAll; 
    private JButton deactivateRule;
    private JComboBox<String> addToDictionary; 
    private JButton change; 
    private JButton changeAll; 
    private JButton help; 
    private JButton options; 
    private JButton undo; 
    private JButton close; 
    
    private SingleDocument currentDocument;
    private ViewCursorTools viewCursor;
    private SingleProofreadingError error;
    private Map<Integer, Set<Integer>> ignoredSpellMatches;
    private String[] userDictionaries;
    private String informationUrl;
    private String docId;
    private String lastLang = new String();
    private String endOfDokumentMessage;
    private int x;
    private int y;
    private int endOfRange = -1;
    private int lastFlatPara = -1;
    private int nFPara = 0;
    private boolean isSpellError = false;
    private boolean focusLost = false;
    private String wrongWord;
    private List<UndoContainer> undoList;
    private Locale locale;

    /**
     * the constructor of the class creates all elements of the dialog
     */
    public LtCheckDialog(XComponentContext xContext) {
      int begFirstCol = 10;
      int widFirstCol = 440;
      int disFirstCol = 10;
      int buttonHigh = 30;
      int begSecondCol = 460;
      int buttonWidthCol = 160;
      int buttonDistCol = 10;
      int buttonWidthRow = 120;
      int buttonDistRow = (begSecondCol + buttonWidthCol - begFirstCol - 4 * buttonWidthRow) / 3;
      if (debugMode) {
        MessageHandler.printToLogFile("LtCheckDialog called");
      }
      currentDocument = getCurrentDocument();
      docId = currentDocument.getDocID();
      ignoredSpellMatches = new HashMap<>();
      undoList = new ArrayList<UndoContainer>();
      setUserDictionaries();

      dialog = new JDialog();
      if (dialog == null) {
        MessageHandler.printToLogFile("LtCheckDialog == null");
      }
      dialog.setName(dialogName);
      dialog.setTitle(dialogName);
      dialog.setLayout(null);
      dialog.setSize(640, 480);

      languageLabel = new JLabel(labelLanguage);
      Font dialogFont = languageLabel.getFont().deriveFont((float) 12);
      languageLabel.setBounds(begFirstCol, disFirstCol, 180, 30);
      languageLabel.setFont(dialogFont);
      dialog.add(languageLabel);

      language = new JComboBox<String>(getPossibleLanguages());
      language.setFont(dialogFont);
      language.setBounds(190, disFirstCol, widFirstCol + begFirstCol - 190, 30);
      language.setToolTipText(formatToolTipText(languageHelp));
      language.addItemListener(e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
          String selectedLang = (String) language.getSelectedItem();
          if (!lastLang.equals(selectedLang)) {
            changeLanguage.setEnabled(true);
          }
        }
      });
      dialog.add(language);

      changeLanguage = new JComboBox<String> (changeLanguageList);
      changeLanguage.setFont(dialogFont);
      changeLanguage.setBounds(begSecondCol, disFirstCol, buttonWidthCol, buttonHigh);
      changeLanguage.setToolTipText(formatToolTipText(changeLanguageHelp));
      changeLanguage.addItemListener(e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
          Locale locale = null;
          FlatParagraphTools flatPara= null;
          int nFlat= 0;
          if (changeLanguage.getSelectedIndex() > 0) {
            String selectedLang = (String) language.getSelectedItem();
            locale = this.getLocaleFromLanguageName(selectedLang);
            flatPara = currentDocument.getFlatParagraphTools();
            nFlat = lastFlatPara < 0 ? docCache.getFlatParagraphNumber(y) : lastFlatPara;
            if (changeLanguage.getSelectedIndex() == 1) {
              flatPara.setLanguageOfParagraph(nFlat, error.nErrorStart, error.nErrorLength, locale);
              addLanguageChangeUndo(nFlat, error.nErrorStart, error.nErrorLength, lastLang);
            } else if (changeLanguage.getSelectedIndex() == 2) {
              flatPara.setLanguageOfParagraph(nFlat, 0, docCache.getFlatParagraph(nFlat).length(), locale);
              docCache.setFlatParagraphLocale(nFlat, locale);
              addLanguageChangeUndo(nFlat, 0, docCache.getFlatParagraph(nFlat).length(), lastLang);
            }
            lastLang = selectedLang;
            changeLanguage.setSelectedIndex(0);
            gotoNextError(true);
          }
        }
      });
      changeLanguage.setSelectedIndex(0);
      changeLanguage.setEnabled(false);
      dialog.add(changeLanguage);
      
      int yFirstCol = 2 * disFirstCol + 30;
      errorDescription = new JTextArea();
      errorDescription.setEditable(false);
      errorDescription.setLineWrap(true);
      errorDescription.setWrapStyleWord(true);
      errorDescription.setBackground(dialog.getContentPane().getBackground());
      Font descriptionFont = dialogFont.deriveFont(Font.BOLD);
      errorDescription.setFont(descriptionFont);
      errorDescription.setToolTipText(formatToolTipText(matchDescriptionHelp));
      JScrollPane descriptionPane = new JScrollPane(errorDescription);
      descriptionPane.setBounds(begFirstCol, yFirstCol, widFirstCol, 40);
      dialog.add(descriptionPane);

      yFirstCol += disFirstCol + 40;
      sentenceIncludeError = new JTextPane();
      sentenceIncludeError.setFont(dialogFont);
      sentenceIncludeError.setToolTipText(formatToolTipText(matchParagraphHelp));
      sentenceIncludeError.getDocument().addDocumentListener(new DocumentListener() {
        @Override
        public void changedUpdate(DocumentEvent e) {
          if (!change.isEnabled()) {
            change.setEnabled(true);
          }
          if (changeAll.isEnabled()) {
            changeAll.setEnabled(false);
          }
        }
        @Override
        public void insertUpdate(DocumentEvent e) {
          changedUpdate(e);
        }
        @Override
        public void removeUpdate(DocumentEvent e) {
          changedUpdate(e);
        }
      });
      JScrollPane sentencePane = new JScrollPane(sentenceIncludeError);
      sentencePane.setBounds(begFirstCol, yFirstCol, widFirstCol, 110);
      dialog.add(sentencePane);
      
      yFirstCol += disFirstCol + 110;
      suggestionsLabel = new JLabel(labelSuggestions);
      suggestionsLabel.setFont(dialogFont);
      suggestionsLabel.setBounds(begFirstCol, yFirstCol, widFirstCol, 15);
      dialog.add(suggestionsLabel);

      yFirstCol += disFirstCol + 15;
      suggestions = new JList<String>();
      suggestions.setFont(dialogFont);
      suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      suggestions.setFixedCellHeight((int)(suggestions.getFont().getSize() * 1.2 + 0.5));
      suggestions.setToolTipText(formatToolTipText(suggestionsHelp));
      JScrollPane suggestionsPane = new JScrollPane(suggestions);
      suggestionsPane.setBounds(begFirstCol, yFirstCol, widFirstCol, 100);
      dialog.add(suggestionsPane);
      
      yFirstCol += disFirstCol + 100;
      checkTypeLabel = new JLabel(Tools.getLabel(messages.getString("guiOOoCheckTypeLabel")));
      checkTypeLabel.setFont(dialogFont);
      checkTypeLabel.setBounds(begFirstCol, yFirstCol, 3*widFirstCol/16 - 1, 30);
      checkTypeLabel.setToolTipText(formatToolTipText(checkTypeHelp));
      dialog.add(checkTypeLabel);

      checkTypeButtons = new JRadioButton[3];
      checkTypeGroup = new ButtonGroup();
      checkTypeButtons[0] = new JRadioButton(Tools.getLabel(messages.getString("guiOOoCheckAllButton")));
      checkTypeButtons[0].setBounds(begFirstCol + 3*widFirstCol/16, yFirstCol, 3*widFirstCol/16 - 1, 30);
      checkTypeButtons[0].setSelected(true);
      checkTypeButtons[0].addActionListener(e -> {
        checkType = 0;
        gotoNextError(true);
      });
      checkTypeButtons[1] = new JRadioButton(Tools.getLabel(messages.getString("guiOOoCheckSpellingButton")));
      checkTypeButtons[1].setBounds(begFirstCol + 6*widFirstCol/16, yFirstCol, 5*widFirstCol/16 - 1, 30);
      checkTypeButtons[1].addActionListener(e -> {
        checkType = 1;
        gotoNextError(true);
      });
      checkTypeButtons[2] = new JRadioButton(Tools.getLabel(messages.getString("guiOOoCheckGrammarButton")));
      checkTypeButtons[2].setBounds(begFirstCol + 11*widFirstCol/16, yFirstCol, 5*widFirstCol/16 - 1, 30);
      checkTypeButtons[2].addActionListener(e -> {
        checkType = 2;
        gotoNextError(true);
      });
      for (int i = 0; i < 3; i++) {
        checkTypeGroup.add(checkTypeButtons[i]);
        checkTypeButtons[i].setFont(dialogFont);
        checkTypeButtons[i].setToolTipText(formatToolTipText(checkTypeHelp));
        dialog.add(checkTypeButtons[i]);
      }

      yFirstCol += 2 * disFirstCol + 30;
      help = new JButton (helpButtonName);
      help.setFont(dialogFont);
      help.setBounds(begFirstCol, yFirstCol, buttonWidthRow, buttonHigh);
      help.addActionListener(this);
      help.setActionCommand("help");
      help.setToolTipText(formatToolTipText(helpButtonHelp));
      dialog.add(help);
      
      int xButtonRow = begFirstCol + buttonWidthRow + buttonDistRow;
      options = new JButton (optionsButtonName);
      options.setFont(dialogFont);
      options.setBounds(xButtonRow, yFirstCol, buttonWidthRow, buttonHigh);
      options.addActionListener(this);
      options.setActionCommand("options");
      options.setToolTipText(formatToolTipText(optionsButtonHelp));
      dialog.add(options);
      
      xButtonRow += buttonWidthRow + buttonDistRow;
      undo = new JButton (undoButtonName);
      undo.setFont(dialogFont);
      undo.setBounds(xButtonRow, yFirstCol, buttonWidthRow, buttonHigh);
      undo.addActionListener(this);
      undo.setActionCommand("undo");
      undo.setToolTipText(formatToolTipText(undoButtonHelp));
      dialog.add(undo);
      
      xButtonRow += buttonWidthRow + buttonDistRow;
      close = new JButton (closeButtonName);
      close.setFont(dialogFont);
      close.setBounds(xButtonRow, yFirstCol, buttonWidthRow, buttonHigh);
      close.addActionListener(this);
      close.setActionCommand("close");
      close.setToolTipText(formatToolTipText(closeButtonHelp));
      dialog.add(close);
      
      int ySecondCol = 2 * disFirstCol + 30;
      more = new JButton (moreButtonName);
      more.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      more.setFont(dialogFont);
      more.addActionListener(this);
      more.setActionCommand("more");
      more.setToolTipText(formatToolTipText(moreButtonHelp));
      dialog.add(more);
      
      ySecondCol += disFirstCol + 40;
      ignoreOnce = new JButton (ignoreButtonName);
      ignoreOnce.setFont(dialogFont);
      ignoreOnce.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      ignoreOnce.addActionListener(this);
      ignoreOnce.setActionCommand("ignoreOnce");
      ignoreOnce.setToolTipText(formatToolTipText(ignoreButtonHelp));
      dialog.add(ignoreOnce);
      
      ySecondCol += buttonDistCol + buttonHigh;
      ignoreAll = new JButton (ignoreAllButtonName);
      ignoreAll.setFont(dialogFont);
      ignoreAll.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      ignoreAll.addActionListener(this);
      ignoreAll.setActionCommand("ignoreAll");
      ignoreAll.setToolTipText(formatToolTipText(ignoreAllButtonHelp));
      dialog.add(ignoreAll);
      
      ySecondCol += buttonDistCol + buttonHigh;
      deactivateRule = new JButton (deactivateRuleButtonName);
      deactivateRule.setFont(dialogFont);
      deactivateRule.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      deactivateRule.setVisible(false);
      deactivateRule.addActionListener(this);
      deactivateRule.setActionCommand("deactivateRule");
      deactivateRule.setToolTipText(formatToolTipText(deactivateRuleButtonHelp));
      dialog.add(deactivateRule);
      
      addToDictionary = new JComboBox<String> (userDictionaries);
      addToDictionary.setFont(dialogFont);
      addToDictionary.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      addToDictionary.setToolTipText(formatToolTipText(addToDictionaryHelp));
      addToDictionary.addItemListener(e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
          if (addToDictionary.getSelectedIndex() > 0) {
            String dictionary = (String) addToDictionary.getSelectedItem();
            documents.getLtDictionary().addWordToDictionary(dictionary, wrongWord, xContext);
            addUndo(y, "addToDictionary", dictionary, wrongWord);
            addToDictionary.setSelectedIndex(0);
            gotoNextError(true);
          }
        }
      });

      dialog.add(addToDictionary);
      
      ySecondCol += 4*buttonDistCol + buttonHigh;
      change = new JButton (changeButtonName);
      change.setFont(dialogFont);
      change.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      change.addActionListener(this);
      change.setActionCommand("change");
      change.setToolTipText(formatToolTipText(changeButtonHelp));
      dialog.add(change);
      
      ySecondCol += buttonDistCol + buttonHigh;
      changeAll = new JButton (changeAllButtonName);
      changeAll.setFont(dialogFont);
      changeAll.setBounds(begSecondCol, ySecondCol, buttonWidthCol, buttonHigh);
      changeAll.addActionListener(this);
      changeAll.setActionCommand("changeAll");
      changeAll.setEnabled(false);
      changeAll.setToolTipText(formatToolTipText(changeAllButtonHelp));
      dialog.add(changeAll);

      dialog.addWindowFocusListener(new WindowFocusListener() {
        @Override
        public void windowGainedFocus(WindowEvent e) {
          if (focusLost) {
            if (debugMode) {
              MessageHandler.printToLogFile("Check Dialog: Window Focus gained: Event = " + e.paramString());
            }
            currentDocument = getCurrentDocument();
            String newDocId = currentDocument.getDocID();
            if (debugMode) {
              MessageHandler.printToLogFile("Check Dialog: Window Focus gained: new docID = " + newDocId + ", old = " + docId);
            }
            if (!docId.equals(newDocId)) {
              docId = newDocId;
              undoList = new ArrayList<UndoContainer>();
            }
            dialog.setEnabled(false);
            initCursor();
            gotoNextError(false);
            dialog.setEnabled(true);
            focusLost = false;
          }
        }
        @Override
        public void windowLostFocus(WindowEvent e) {
          if (debugMode) {
            MessageHandler.printToLogFile("Check Dialog: Window Focus lost: Event = " + e.paramString());
          }
          focusLost = true;
        }
      });
      
      ToolTipManager.sharedInstance().setDismissDelay(30000);
    }

    /**
     * show the dialog
     */
    public void show() {
      if (debugMode) {
        MessageHandler.printToLogFile("Check Dialog: Goto next Error");
      }
      dialog.setEnabled(false);
      initCursor();
      gotoNextError(false);
      if (documents.useOriginalCheckDialog()) {
        OfficeTools.dispatchCmd(".uno:SpellingAndGrammarDialog", xContext);
        closeDialog();
        return;
      }
      dialog.setEnabled(true);
      Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
      Dimension frameSize = dialog.getSize();
      dialog.setLocation(screenSize.width / 2 - frameSize.width / 2,
          screenSize.height / 2 - frameSize.height / 2);
      documents.setLtDialog(this);
      dialog.setAutoRequestFocus(true);
      dialog.setVisible(true);
      dialog.toFront();
    }

    /**
     * Initialize the cursor / define the range for check
     */
    private void initCursor() {
      viewCursor = new ViewCursorTools(xContext);
      XTextCursor tCursor = viewCursor.getTextCursorBeginn();
      tCursor.gotoStart(true);
      int nBegin = tCursor.getString().length();
      tCursor = viewCursor.getTextCursorEnd();
      tCursor.gotoStart(true);
      int nEnd = tCursor.getString().length();
      if (nBegin < nEnd) {
        endOfRange = nEnd;
      } else {
        endOfRange = -1;
      }
      lastFlatPara = -1;
    }

    /**
     * Formats the tooltip text
     * The text is given by a text string which is formated into html:
     * \n are formated to html paragraph breaks
     * \n- is formated to an unordered List
     * \n1. is formated to an ordered List (every digit 1 - 9 is allowed 
     */
    private String formatToolTipText(String Text) {
      String toolTipText = Text;
      int breakIndex = 0;
      int isNum = 0;
      while (breakIndex >= 0) {
        breakIndex = toolTipText.indexOf("\n", breakIndex);
        if (breakIndex >= 0) {
          int nextNonBlank = breakIndex + 1;
          while (' ' == toolTipText.charAt(nextNonBlank)) {
            nextNonBlank++;
          }
          if (isNum == 0) {
            if (toolTipText.charAt(nextNonBlank) == '-') {
              toolTipText = toolTipText.substring(0, breakIndex) + "</p><ul width=\"" 
                  + toolTipWidth + "\"><li>" + toolTipText.substring(nextNonBlank + 1);
              isNum = 1;
            } else if (toolTipText.charAt(nextNonBlank) >= '1' && toolTipText.charAt(nextNonBlank) <= '9' 
                            && toolTipText.charAt(nextNonBlank + 1) == '.') {
              toolTipText = toolTipText.substring(0, breakIndex) + "</p><ol width=\"" 
                  + toolTipWidth + "\"><li>" + toolTipText.substring(nextNonBlank + 2);
              isNum = 2;
            } else {
              toolTipText = toolTipText.substring(0, breakIndex) + "</p><p width=\"" 
                              + toolTipWidth + "\">" + toolTipText.substring(breakIndex + 1);
            }
          } else if (isNum == 1) {
            if (toolTipText.charAt(nextNonBlank) == '-') {
              toolTipText = toolTipText.substring(0, breakIndex) + "</li><li>" + toolTipText.substring(nextNonBlank + 1);
            } else {
              toolTipText = toolTipText.substring(0, breakIndex) + "</li></ul><p width=\"" 
                  + toolTipWidth + "\">" + toolTipText.substring(breakIndex + 1);
              isNum = 0;
            }
          } else {
            if (toolTipText.charAt(nextNonBlank) >= '1' && toolTipText.charAt(nextNonBlank) <= '9' 
                && toolTipText.charAt(nextNonBlank + 1) == '.') {
              toolTipText = toolTipText.substring(0, breakIndex) + "</li><li>" + toolTipText.substring(nextNonBlank + 2);
            } else {
              toolTipText = toolTipText.substring(0, breakIndex) + "</li></ol><p width=\"" 
                  + toolTipWidth + "\">" + toolTipText.substring(breakIndex + 1);
              isNum = 0;
            }
          }
        }
      }
      if (isNum == 0) {
        toolTipText = "<html><p width=\"" + toolTipWidth + "\">" + toolTipText + "</p></html>";
      } else if (isNum == 1) {
        toolTipText = "<html><p width=\"" + toolTipWidth + "\">" + toolTipText + "</ul></html>";
      } else {
        toolTipText = "<html><p width=\"" + toolTipWidth + "\">" + toolTipText + "</ol></html>";
      }
      return toolTipText;
    }

    /**
     * find the next match
     * set the view cursor to the position of match
     * fill the elements of the dialog with the information of the match
     */
    private void gotoNextError(boolean startAtBegin) {
      ignoreOnce.setEnabled(false);
      ignoreAll.setEnabled(false);
      deactivateRule.setEnabled(false);
      change.setEnabled(false);
      changeAll.setVisible(false);
      addToDictionary.setEnabled(false);
      more.setEnabled(false);
      help.setEnabled(false);
      options.setEnabled(false);
      undo.setEnabled(false);
      close.setEnabled(false);
      language.setEnabled(false);
      changeLanguage.setEnabled(false);
      endOfDokumentMessage = null;
      errorDescription.setForeground(Color.BLACK);
      CheckError checkError = getNextError(startAtBegin);
      error = checkError == null ? null : checkError.error;
      locale = checkError == null ? null : checkError.locale;
      help.setEnabled(true);
      options.setEnabled(true);
      close.setEnabled(true);
      if (sentenceIncludeError == null || errorDescription == null || suggestions == null) {
        MessageHandler.printToLogFile("SentenceIncludeError == null || errorDescription == null || suggestions == null");
      } else if (error != null) {
        
        ignoreOnce.setEnabled(true);
        ignoreAll.setEnabled(true);

        isSpellError = error.aRuleIdentifier.equals(spellRuleId);

        if (lastFlatPara < 0) {
          sentenceIncludeError.setText(docCache.getTextParagraph(y));
        } else {
          sentenceIncludeError.setText(docCache.getFlatParagraph(lastFlatPara));
        }
        setAttributesForErrorText(error);

        errorDescription.setText(error.aFullComment);
        
        if (error.aSuggestions != null && error.aSuggestions.length > 0) {
          suggestions.setListData(error.aSuggestions);
          suggestions.setSelectedIndex(0);
          change.setEnabled(true);
          changeAll.setEnabled(true);
        } else {
          suggestions.setListData(new String[0]);
          change.setEnabled(false);
          changeAll.setEnabled(false);
        }
        
        Language lang = locale == null ? langTool.getLanguage() : documents.getLanguage(locale);
        if (debugMode && langTool.getLanguage() == null) {
          MessageHandler.printToLogFile("LT language == null");
        }
        lastLang = lang.getTranslatedName(messages);
        language.setEnabled(true);
        language.setSelectedItem(lang.getTranslatedName(messages));
        
        if (isSpellError) {
          addToDictionary.setVisible(true);
          changeAll.setVisible(true);
          deactivateRule.setVisible(false);
          addToDictionary.setEnabled(true);
          changeAll.setEnabled(true);
        } else {
          addToDictionary.setVisible(false);
          changeAll.setVisible(false);
          deactivateRule.setVisible(true);
          deactivateRule.setEnabled(true);
        }
        informationUrl = getUrl(error);
        more.setVisible(informationUrl != null);
        more.setEnabled(informationUrl != null);
        undo.setEnabled(undoList != null && !undoList.isEmpty());
      } else {
        ignoreOnce.setEnabled(false);
        ignoreAll.setEnabled(false);
        deactivateRule.setEnabled(false);
        change.setEnabled(false);
        changeAll.setVisible(false);
        addToDictionary.setVisible(false);
        deactivateRule.setVisible(true);
        more.setVisible(false);
        focusLost = false;
        suggestions.setListData(new String[0]);
        undo.setEnabled(undoList != null && !undoList.isEmpty());
        errorDescription.setForeground(Color.RED);
        errorDescription.setText(endOfDokumentMessage == null ? "" : endOfDokumentMessage);
        sentenceIncludeError.setText("");
        if (docCache.size() > 0) {
          locale = docCache.getFlatParagraphLocale(docCache.size() - 1);
        }
        Language lang = locale == null || !documents.hasLocale(locale)? langTool.getLanguage() : documents.getLanguage(locale);
        language.setSelectedItem(lang.getTranslatedName(messages));
      }
    }

    /**
     * stores the list of local dictionaries into the dialog element
     */
    private void setUserDictionaries () {
      String[] tmpDictionaries = documents.getLtDictionary().getUserDictionaries(xContext);
      userDictionaries = new String[tmpDictionaries.length + 1];
      userDictionaries[0] = addToDictionaryName;
      for (int i = 0; i < tmpDictionaries.length; i++) {
        userDictionaries[i + 1] = tmpDictionaries[i];
      }
    }

    /**
     * returns an array of the translated names of the languages supported by LT
     */
    private String[] getPossibleLanguages() {
      List<String> languages = new ArrayList<>();
      for (Language lang : Languages.get()) {
        languages.add(lang.getTranslatedName(messages));
        languages.sort(null);
      }
      return languages.toArray(new String[languages.size()]);
    }

    /**
     * returns the locale from a translated language name 
     */
    private Locale getLocaleFromLanguageName(String translatedName) {
      for (Language lang : Languages.get()) {
        if (translatedName.equals(lang.getTranslatedName(messages))) {
          return (LinguisticServices.getLocale(lang));
        }
      }
      return null;
    }

    /**
     * set the attributes for the text inside the editor element
     */
    private void setAttributesForErrorText(SingleProofreadingError error) {
      //  Get Attributes
      MutableAttributeSet attrs = sentenceIncludeError.getInputAttributes();
      StyledDocument doc = sentenceIncludeError.getStyledDocument();
      //  Set back to default values
      StyleConstants.setBold(attrs, false);
      StyleConstants.setUnderline(attrs, false);
      StyleConstants.setForeground(attrs, Color.BLACK);
      doc.setCharacterAttributes(0, doc.getLength() + 1, attrs, true);
      //  Set values for error
      StyleConstants.setBold(attrs, true);
      StyleConstants.setUnderline(attrs, true);
      Color color = null;
      if (isSpellError) {
        color = Color.RED;
      } else {
        PropertyValue[] properties = error.aProperties;
        for (PropertyValue property : properties) {
          if ("LineColor".equals(property.Name)) {
            color = new Color((int) property.Value);
            break;
          }
        }
        if (color == null) {
          color = Color.BLUE;
        }
      }
      StyleConstants.setForeground(attrs, color);
      doc.setCharacterAttributes(error.nErrorStart, error.nErrorLength, attrs, true);
    }

    /**
     * returns the URL to more information of match
     * returns null, if such an URL does not exist
     */
    private String getUrl(SingleProofreadingError error) {
      if (!isSpellError) {
        PropertyValue[] properties = error.aProperties;
        for (PropertyValue property : properties) {
          if ("FullCommentURL".equals(property.Name)) {
            String url = new String((String) property.Value);
            return url;
          }
        }
      }
      return null;
    }
    
    /**
     * returns the next match
     * starting at the current cursor position
     */
    private CheckError getNextError(boolean startAtBegin) {
      currentDocument = getCurrentDocument();
      XComponent xComponent = currentDocument.getXComponent();
      DocumentCursorTools docCursor = new DocumentCursorTools(xComponent);
      docCache = updateDocumentCache(nFPara, xComponent, docCursor, currentDocument);
      if (docCache.size() <= 0) {
        MessageHandler.printToLogFile("getNextError: docCache size == 0: Return null");
        return null;
      }
      y = viewCursor.getViewCursorParagraph();
      if (y >= docCache.textSize()) {
        MessageHandler.printToLogFile("getNextError: y (= " + y + ") >= text size (= " + docCache.textSize() + "): Return null");
        endOfDokumentMessage = messages.getString("guiCheckComplete");
        return null;
      }
      x = viewCursor.getViewCursorCharacter();
      if (startAtBegin) {
        x = 0;
      }
      int nStart = 0;
      for (int i = 0; i <= y && y < docCache.textSize(); i++) {
        nStart += docCache.getTextParagraph(i).length() + 1;
      }
      CheckError nextError = null;
      if (lastFlatPara < 0) {
        nFPara = docCache.getFlatParagraphNumber(y);
        nextError = getNextErrorInParagraph (x, nFPara, currentDocument, docCursor, ignoredSpellMatches);
        int pLength = docCache.getTextParagraph(y).length() + 1;
        while (y < docCache.textSize() - 1 && nextError == null && (endOfRange < 0 || nStart < endOfRange)) {
          y++;
          nFPara = docCache.getFlatParagraphNumber(y);
          nextError = getNextErrorInParagraph (0, nFPara, currentDocument, docCursor,ignoredSpellMatches);
          pLength = docCache.getTextParagraph(y).length() + 1;
          nStart += pLength;
        }
        if (nextError != null && (endOfRange < 0 || nStart - pLength + nextError.error.nErrorStart < endOfRange)) {
          if (nextError.error.aRuleIdentifier.equals(spellRuleId)) {
            wrongWord = docCache.getTextParagraph(y).substring(nextError.error.nErrorStart, 
                nextError.error.nErrorStart + nextError.error.nErrorLength);
          }
          if (debugMode) {
            MessageHandler.printToLogFile("endOfRange:" + endOfRange + "; ErrorStart(" + nStart + "/" + pLength + "/" 
                + nextError.error.nErrorStart + "): " + (nStart - pLength + nextError.error.nErrorStart));
            MessageHandler.printToLogFile("x: " + x + "; y: " + y);
          }
          setTextViewCursor(nextError.error.nErrorStart, y, viewCursor, docCursor);
          return nextError;
        }
      }
      if (endOfRange < 0) {
        if (docCache.textSize() < docCache.size()) {
          if (lastFlatPara < 0) {
            lastFlatPara = 0;
          }
          while (lastFlatPara < docCache.size()) {
            if (docCache.getNumberOfTextParagraph(lastFlatPara) < 0) {
              nFPara = lastFlatPara;
              nextError = getNextErrorInParagraph (0, lastFlatPara, currentDocument, docCursor,ignoredSpellMatches);
              if (nextError != null) {
                if (nextError.error.aRuleIdentifier.equals(spellRuleId)) {
                  wrongWord = docCache.getFlatParagraph(lastFlatPara).substring(nextError.error.nErrorStart, 
                      nextError.error.nErrorStart + nextError.error.nErrorLength);
                }
                setFlatViewCursor(nextError.error.nErrorStart, lastFlatPara, viewCursor, docCache, docCursor);
                if (debugMode) {
                  MessageHandler.printToLogFile("lastFlatPara:" + lastFlatPara + ", ErrorStart: " + nextError.error.nErrorStart 
                      + ", ErrorLength: " + nextError.error.nErrorLength);
                }
                return nextError;
              }
            }
            lastFlatPara++;
          }
        }
        lastFlatPara = -1;
        endOfDokumentMessage = messages.getString("guiCheckComplete");
      } else {
        endOfDokumentMessage = messages.getString("guiSelectionCheckComplete");
      }
      return null;
    }

    /**
     * Actions of buttons
     */
    @Override
    public void actionPerformed(ActionEvent action) {
      if (debugMode) {
        MessageHandler.printToLogFile("Action: " + action);
      }
      if (action.getActionCommand().equals("close")) {
        closeDialog();
      } else if (action.getActionCommand().equals("ignoreOnce")) {
        ignoreOnce();
      } else if (action.getActionCommand().equals("ignoreAll")) {
        ignoreAll();
      } else if (action.getActionCommand().equals("deactivateRule")) {
        deactivateRule();
      } else if (action.getActionCommand().equals("change")) {
        changeText();
      } else if (action.getActionCommand().equals("changeAll")) {
        changeAll();
      } else if (action.getActionCommand().equals("undo")) {
        undo();
      } else if (action.getActionCommand().equals("more")) {
        Tools.openURL(informationUrl);
      } else if (action.getActionCommand().equals("options")) {
        documents.runOptionsDialog();
      } else if (action.getActionCommand().equals("help")) {
        MessageHandler.showMessage(messages.getString("loDialogHelpText"));
      } else {
        MessageHandler.showMessage("Action '" + action.getActionCommand() + "' not supported");
      }
    }

    /**
     * closes the dialog
     */
    public void closeDialog() {
      if (debugMode) {
        MessageHandler.printToLogFile("Close Spell And Grammar Check Dialog");
      }
      undoList = null;
      documents.setLtDialog(null);
      documents.setLtDialogIsRunning(false);
      dialog.setVisible(false);
    }
    
    /**
     * remove a mark for spelling error in document
     * TODO: The function works very temporarily
     */
    private void removeSpellingMark(int nFlat) {
      DocumentCursorTools docCursor = new DocumentCursorTools(currentDocument.getXComponent());
      XParagraphCursor pCursor = docCursor.getParagraphCursor();
      XTextViewCursor vCursor = viewCursor.getViewCursor();
      pCursor.gotoRange(vCursor, false);
      pCursor.gotoStartOfParagraph(false);
      pCursor.goRight((short)error.nErrorStart, false);
      pCursor.goRight((short)error.nErrorLength, true);
      XMarkingAccess xMarkingAccess = UnoRuntime.queryInterface(XMarkingAccess.class, pCursor);
      if (xMarkingAccess == null) {
        MessageHandler.printToLogFile("xMarkingAccess == null");
      } else {
        xMarkingAccess.invalidateMarkings(TextMarkupType.SPELLCHECK);
      }
    }

    /**
     * set the information to ignore just the match at the given position
     */
    private void ignoreOnce() {
      if (lastFlatPara < 0) {
        y = docCache.getFlatParagraphNumber(viewCursor.getViewCursorParagraph());
        x = viewCursor.getViewCursorCharacter();
      } else {
        y = lastFlatPara;
        x = error.nErrorStart;
      }
      if (isSpellError) {
        if (ignoredSpellMatches.containsKey(y)) {
          Set<Integer> charNums = ignoredSpellMatches.get(y);
          charNums.add(x);
          ignoredSpellMatches.put(y, charNums);
        } else {
          Set<Integer> charNums = new HashSet<>();
          charNums.add(x);
          ignoredSpellMatches.put(y, charNums);
        }
        removeSpellingMark(y);
      } else {
        currentDocument.setIgnoredMatch(x, y, error.aRuleIdentifier);
      }
      addUndo(x, y, "ignoreOnce", error.aRuleIdentifier);
      gotoNextError(true);
    }

    /**
     * ignore all performed:
     * spelling error: add word to temporary dictionary
     * grammatical error: deactivate rule
     * both actions are only for the current session
     */
    private void ignoreAll() {
      int nFlat = lastFlatPara < 0 ? docCache.getFlatParagraphNumber(y) : lastFlatPara;
      if (isSpellError) {
        if (debugMode) {
          MessageHandler.printToLogFile("Ignored word: " + wrongWord);
        }
        documents.getLtDictionary().addIgnoredWord(wrongWord);
      } else {
        documents.ignoreRule(error.aRuleIdentifier, locale);
        documents.initDocuments();
        documents.resetDocument();
        doInit = true;
      }
      addUndo(nFlat, "ignoreAll", error.aRuleIdentifier);
      gotoNextError(true);
    }

    /**
     * the rule is deactivated permanently (saved in the configuration file)
     */
    private void deactivateRule() {
      if (!isSpellError) {
        documents.deactivateRule(error.aRuleIdentifier, false);
        documents.addDisabledRule(error.aRuleIdentifier);
        documents.initDocuments();
        documents.resetDocument();
        int nFlat = lastFlatPara < 0 ? docCache.getFlatParagraphNumber(y) : lastFlatPara;
        addUndo(nFlat, "deactivateRule", error.aRuleIdentifier);
        doInit = true;
      }
      gotoNextError(true);
    }

    /**
     * compares two strings from the beginning
     * returns the first different character 
     */
    private int getDifferenceFromBegin(String text1, String text2) {
      for (int i = 0; i < text1.length() && i < text2.length(); i++) {
        if (text1.charAt(i) != text2.charAt(i)) {
          return i;
        }
      }
      return (text1.length() < text2.length() ? text1.length() : text2.length());
    }

    /**
     * compares two strings from the end
     * returns the first different character 
     */
    private int getDifferenceFromEnd(String text1, String text2) {
      for (int i = 1; i <= text1.length() && i <= text2.length(); i++) {
        if (text1.charAt(text1.length() - i) != text2.charAt(text2.length() - i)) {
          return text1.length() - i + 1;
        }
      }
      return (text1.length() < text2.length() ? 0 : text1.length() - text2.length());
    }

    /**
     * change the text of the paragraph inside the document
     * use the difference between the original paragraph and the text inside the editor element
     * or if there is no difference replace the match by the selected suggestion
     */
    private void changeText() {
      String word;
      String replace;
      String orgText;
      String dialogText = sentenceIncludeError.getText();
      if (lastFlatPara < 0) {
        orgText = docCache.getTextParagraph(y);
        XParagraphCursor pCursor = viewCursor.getParagraphCursorFromViewCursor();
        pCursor.gotoStartOfParagraph(false);
        if (!orgText.equals(dialogText)) {
          int firstChange = getDifferenceFromBegin(orgText, dialogText);
          int lastEqual = getDifferenceFromEnd(orgText, dialogText);
          int nDiff = lastEqual - firstChange;
          pCursor.goRight((short)firstChange, false);
          if (nDiff < orgText.length() - dialogText.length()) {
            nDiff = orgText.length() - dialogText.length();
            lastEqual = firstChange + nDiff;
          }
          if (nDiff > 0) {
            pCursor.goRight((short)nDiff, true);
          }
          int lastDialogEqual = dialogText.length() - orgText.length() + lastEqual;
          if (lastDialogEqual - firstChange < dialogText.length() - orgText.length()) {
            lastDialogEqual = firstChange + dialogText.length() - orgText.length();
          }
          replace = dialogText.substring(firstChange, lastDialogEqual);
          word = pCursor.getString();
          pCursor.setString(replace);
          addSingleChangeUndo(firstChange, docCache.getFlatParagraphNumber(y), word, replace);
        } else if (suggestions.getComponentCount() > 0) {
          pCursor.goRight((short)error.nErrorStart, false);
          pCursor.goRight((short)error.nErrorLength, true);
          replace = suggestions.getSelectedValue();
          word = pCursor.getString();
          pCursor.setString(replace);
          addSingleChangeUndo(error.nErrorStart, docCache.getFlatParagraphNumber(y), word, replace);
        } else {
          MessageHandler.printToLogFile("No text selected to change");
          return;
        }
        int nFlat = docCache.getFlatParagraphNumber(y);
        docCache.setFlatParagraph(nFlat, dialogText);
        currentDocument.getDocumentCache().setFlatParagraph(nFlat, dialogText);
        currentDocument.removeResultCache(nFlat);
        currentDocument.removeIgnoredMatch(nFlat);
        ignoredSpellMatches.remove(nFlat);
      } else {
        FlatParagraphTools flatPara = currentDocument.getFlatParagraphTools();
        orgText = docCache.getFlatParagraph(lastFlatPara);
        if (!orgText.equals(dialogText)) {
          int firstChange = getDifferenceFromBegin(orgText, dialogText);
          int lastEqual = getDifferenceFromEnd(orgText, dialogText);
          int lastDialogEqual = dialogText.length() - orgText.length() + lastEqual;
          word = orgText.substring(firstChange, lastEqual);
          replace = dialogText.substring(firstChange, lastDialogEqual);
          flatPara.changeTextOfParagraph(lastFlatPara, firstChange, lastEqual - firstChange, replace);
          addSingleChangeUndo(firstChange, lastFlatPara, word, replace);
        } else if (suggestions.getComponentCount() > 0) {
          word = orgText.substring(error.nErrorStart, error.nErrorStart + error.nErrorLength);
          replace = suggestions.getSelectedValue();
          flatPara.changeTextOfParagraph(lastFlatPara, error.nErrorStart, error.nErrorLength, replace);
          addSingleChangeUndo(error.nErrorStart, lastFlatPara, word, replace);
        } else {
          MessageHandler.printToLogFile("No text selected to change");
          return;
        }
        docCache.setFlatParagraph(lastFlatPara, dialogText);
        currentDocument.getDocumentCache().setFlatParagraph(lastFlatPara, dialogText);
        currentDocument.removeResultCache(lastFlatPara);
        currentDocument.removeIgnoredMatch(lastFlatPara);
        ignoredSpellMatches.remove(lastFlatPara);
      }
      if (debugMode) {
        MessageHandler.printToLogFile("Org: " + word + "\nDia: " + replace);
      }
      gotoNextError(true);
    }

    /**
     * Change all matched words of the document by the selected suggestion
     */
    private void changeAll() {
      if (suggestions.getComponentCount() > 0) {
        String orgText = sentenceIncludeError.getText();
        String word = orgText.substring(error.nErrorStart, error.nErrorStart + error.nErrorLength);
        String replace = suggestions.getSelectedValue();
        XComponent xComponent = currentDocument.getXComponent();
        FlatParagraphTools flatPara = currentDocument.getFlatParagraphTools();
        DocumentCursorTools docCursor = new DocumentCursorTools(xComponent);
        Map<Integer, List<Integer>> orgParas = spellChecker.replaceAllWordsInText(word, replace, docCursor, flatPara);
        if (orgParas != null) {
          addChangeUndo(lastFlatPara < 0 ? docCache.getFlatParagraphNumber(y) : lastFlatPara, word, replace, orgParas);
          for (int nFlat : orgParas.keySet()) {
            currentDocument.removeResultCache(nFlat);
          }
        }
        gotoNextError(true);
      }
    }

    /**
     * Add undo information
     * maxUndos changes are stored in the undo list
     */
    private void addUndo(int y, String action, String ruleId) {
      addUndo(0, y, action, ruleId);
    }
    
    private void addUndo(int x, int y, String action, String ruleId) {
      addUndo(x, y, action, ruleId, null);
    }
    
    private void addUndo(int y, String action, String ruleId, String word) {
      addUndo(0, y, action, ruleId, word, null);
    }
    
    private void addUndo(int x, int y, String action, String ruleId, Map<Integer, List<Integer>> orgParas) {
      addUndo(x, y, action, ruleId, null, orgParas);
    }
    
    private void addUndo(int x, int y, String action, String ruleId, String word, Map<Integer, List<Integer>> orgParas) {
      if (undoList.size() >= maxUndos) {
        undoList.remove(0);
      }
      undoList.add(new UndoContainer(x, y, action, ruleId, word, orgParas));
    }

    /**
     * add undo information for change function (general)
     */
    private void addChangeUndo(int y, String word, String replace, Map<Integer, List<Integer>> orgParas) {
      addUndo(0, y, "change", replace, word, orgParas);
    }
    
    /**
     * add undo information for a single change
     */
    private void addSingleChangeUndo(int x, int y, String word, String replace) {
      Map<Integer, List<Integer>> paraMap = new HashMap<Integer, List<Integer>>();
      List<Integer> xVals = new ArrayList<Integer>();
      xVals.add(x);
      paraMap.put(y, xVals);
      addChangeUndo(y, word, replace, paraMap);
    }

    /**
     * add undo information for a language change
     */
    private void addLanguageChangeUndo(int nFlat, int nStart, int nLen, String originalLanguage) {
      Map<Integer, List<Integer>> paraMap = new HashMap<Integer, List<Integer>>();
      List<Integer> xVals = new ArrayList<Integer>();
      xVals.add(nStart);
      xVals.add(nLen);
      paraMap.put(nFlat, xVals);
      addUndo(0, nFlat, "changeLanguage", originalLanguage, null, paraMap);
    }

    /**
     * undo the last change triggered by the LT check dialog
     */
    private void undo() {
      if (undoList == null || undoList.isEmpty()) {
        return;
      }
      try {
        int nLastUndo = undoList.size() - 1;
        UndoContainer lastUndo = undoList.get(nLastUndo);
        String action = lastUndo.action;
        int xUndo = lastUndo.x;
        int yUndo = lastUndo.y;
        XComponent xComponent = currentDocument.getXComponent();
        DocumentCursorTools docCursor = new DocumentCursorTools(xComponent);
        docCache = updateDocumentCache(yUndo, xComponent, docCursor, currentDocument);
        if (debugMode) {
          MessageHandler.printToLogFile("Undo: Action: " + action);
        }
        if (action.equals("ignoreOnce")) {
          if (lastUndo.ruleId.equals(spellRuleId)) {
            if (ignoredSpellMatches.containsKey(yUndo)) {
              Set<Integer> charNums = ignoredSpellMatches.get(yUndo);
              if (charNums.contains(xUndo)) {
                if (charNums.size() < 2) {
                  ignoredSpellMatches.remove(yUndo);
                } else {
                  charNums.remove(xUndo);
                  ignoredSpellMatches.put(yUndo, charNums);
                }
              }
            }
          } else {
            currentDocument.removeIgnoredMatch(xUndo, yUndo, lastUndo.ruleId);
          }
        } else if (action.equals("ignoreAll")) {
          if (lastUndo.ruleId.equals(spellRuleId)) {
            if (debugMode) {
              MessageHandler.printToLogFile("Ignored word removed: " + wrongWord);
            }
            documents.getLtDictionary().removeIgnoredWord(wrongWord);
          } else {
            documents.removeDisabledRule(lastUndo.ruleId);
            documents.initDocuments();
            documents.resetDocument();
            doInit = true;
          }
        } else if (action.equals("deactivateRule")) {
          currentDocument.removeResultCache(yUndo);
          documents.deactivateRule(lastUndo.ruleId, true);
          documents.removeDisabledRule(lastUndo.ruleId);
          documents.initDocuments();
          documents.resetDocument();
          doInit = true;
        } else if (action.equals("addToDictionary")) {
          documents.getLtDictionary().removeWordFromDictionary(lastUndo.ruleId, lastUndo.word, xContext);
        } else if (action.equals("changeLanguage")) {
          Locale locale = getLocaleFromLanguageName(lastUndo.ruleId);
          FlatParagraphTools flatPara = currentDocument.getFlatParagraphTools();
          int nFlat = lastUndo.y;
          int nStart = lastUndo.orgParas.get(nFlat).get(0);
          int nLen = lastUndo.orgParas.get(nFlat).get(1);
          if (debugMode) {
            MessageHandler.printToLogFile("Change Language: Locale: " + locale.Language + "-" + locale.Country 
              + ", nFlat = " + nFlat + ", nStart = " + nStart + ", nLen = " + nLen);
          }
          flatPara.setLanguageOfParagraph(nFlat, nStart, nLen, locale);
          if (nLen == docCache.getFlatParagraph(nFlat).length()) {
            docCache.setFlatParagraphLocale(nFlat, locale);
            currentDocument.getDocumentCache().setFlatParagraphLocale(nFlat, locale);
          }
          currentDocument.removeResultCache(nFlat);
        } else if (action.equals("change")) {
          XParagraphCursor pCursor = docCursor.getParagraphCursor();
          FlatParagraphTools flatPara = currentDocument.getFlatParagraphTools();
          Map<Integer, List<Integer>> paras = lastUndo.orgParas;
          short length = (short) lastUndo.ruleId.length();
          for (int nFlat : paras.keySet()) {
            int n = docCache.getNumberOfTextParagraph(nFlat);
            List<Integer> xStarts = paras.get(nFlat);
            if (debugMode) {
              MessageHandler.printToLogFile("Ignore change: nFlat = " + nFlat + ", n = " + n + ", x = " + xStarts.get(0));
            }
            if (n >= 0) {
              pCursor.gotoStart(false);
              for (int i = 0; i < n; i++) {
                pCursor.gotoNextParagraph(false);
              }
              for (int i = xStarts.size() - 1; i >= 0; i --) {
                int xStart = xStarts.get(i);
                pCursor.gotoStartOfParagraph(false);
                pCursor.goRight((short)xStart, false);
                pCursor.goRight(length, true);
                pCursor.setString(lastUndo.word);
              }
            } else {
              String para = docCache.getFlatParagraph(nFlat);
              for (int i = xStarts.size() - 1; i >= 0; i --) {
                int xStart = xStarts.get(i);
                para = para.substring(0, xStart) + lastUndo.word + para.substring(xStart + length);
                flatPara.changeTextOfParagraph(nFlat, xStart, length, lastUndo.word);
              }
            }
            currentDocument.removeResultCache(nFlat);
          }
        } else {
          MessageHandler.showMessage("Undo '" + action + "' not supported");
        }
        undoList.remove(nLastUndo);
        int yTUndo = docCache.getNumberOfTextParagraph(yUndo);
        if (yTUndo >= 0) {
          lastFlatPara = -1;
          setTextViewCursor(xUndo, yTUndo, viewCursor, docCursor);
        } else {
          lastFlatPara = yUndo;
        }
        if (debugMode) {
          MessageHandler.printToLogFile("yUndo = " + yUndo + ", xUndo = " + xUndo 
              + ", yTundo = " + yTUndo + ", lastFlatPara = " + lastFlatPara);
        }
        gotoNextError(true);
      } catch (Throwable e) {
        MessageHandler.showError(e);
      }
    }
/*    TODO: Delete after tests
    private void showMessage (JDialog parent, String text) {
      parent.toFront();
      JOptionPane optionPane = new JOptionPane(text);
      optionPane.setOptionType(JOptionPane.DEFAULT_OPTION);
      JDialog messageDialog = optionPane.createDialog(parent, "LanguageTool");
      messageDialog.setIconImage(null);
      messageDialog.setAlwaysOnTop(true);
      messageDialog.setVisible(true);
      messageDialog.toFront();
    }

/*
    private void showMessage (Component parent, String text) {
      ShowMessage mess = new ShowMessage (parent, text);
      mess.start();
    }
    
    class ShowMessage extends Thread {
      private final Component parent;
      private final String text;
      
      ShowMessage (Component parent, String text) {
        this.parent = parent;
        this.text = text;
      }
      
      public void run() {
        JOptionPane optionPane = new JOptionPane(text);
        optionPane.setOptionType(JOptionPane.DEFAULT_OPTION);
        messageDialog = optionPane.createDialog(parent, "LanguageTool");
        messageDialog.setIconImage(null);
        messageDialog.setAlwaysOnTop(true);
        messageDialog.setVisible(true);
        messageDialog.toFront();
      }
    }
*/    
  }
  
}
