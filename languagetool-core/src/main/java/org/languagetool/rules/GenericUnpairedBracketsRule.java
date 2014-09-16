/* LanguageTool, a natural language style checker 
 * Copyright (C) 2009 Daniel Naber (http://www.danielnaber.de)
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

package org.languagetool.rules;

import java.util.*;
import java.util.regex.Pattern;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.Language;
import org.languagetool.tools.UnsyncStack;

/**
 * Rule that finds unpaired quotes, brackets etc.
 * 
 * @author Marcin Miłkowski
 */
public class GenericUnpairedBracketsRule extends TextLevelRule {

  private static final Pattern NUMERALS_EN =
          Pattern.compile("(?i)\\d{1,2}?[a-z']*|M*(D?C{0,3}|C[DM])(L?X{0,3}|X[LC])(V?I{0,3}|I[VX])$");
  private static final Pattern PUNCTUATION = Pattern.compile("\\p{Punct}");
  private static final Pattern PUNCTUATION_NO_DOT =
          Pattern.compile("[ldmnst]'|[–—\\p{Punct}&&[^\\.]]");
  // "[ldmnst]'" allows dealing with apostrophed words in Catalan (i.e. l'«home) 

  protected Pattern numerals;
  protected String[] startSymbols;
  protected String[] endSymbols;
  // The stack for pairing symbols:
  protected final UnsyncStack<SymbolLocator> symbolStack = new UnsyncStack<>();

  private final Map<String,Boolean> uniqueMap = new HashMap<>();

  public GenericUnpairedBracketsRule(final ResourceBundle messages, final Language language) {
    super(messages);
    super.setCategory(new Category(messages.getString("category_misc")));
    startSymbols = language.getUnpairedRuleStartSymbols();
    endSymbols = language.getUnpairedRuleEndSymbols();
    numerals = NUMERALS_EN;
    uniqueMapInit();
    setLocQualityIssueType(ITSIssueType.Typographical);
  }

  @Override
  public String getId() {
    return "UNPAIRED_BRACKETS";
  }

  @Override
  public String getDescription() {
    return messages.getString("desc_unpaired_brackets");
  }

  /**
   * @deprecated will become protected (deprecated since 2.7)
   */
  @Deprecated
  public void uniqueMapInit() {
    for (String endSymbol : endSymbols) {
      int found = 0;
      for (String endSymbol1 : endSymbols) {
        if (endSymbol1.equals(endSymbol)) {
          found++;
        }
      }
      uniqueMap.put(endSymbol, found == 1);
    }
  }

  /**
   * Generic method to specify an exception. For unspecified
   * language, it simply returns true (which means no exception) unless
   * there's a common smiley like :-) or ;-).
   * @param token String token
   * @param tokens Sentence tokens
   * @param i Current token index
   * @param precSpace is preceded with space
   * @param follSpace is followed with space
   */
  protected boolean isNoException(final String token,
                                  final AnalyzedTokenReadings[] tokens, final int i, final int j,
                                  final boolean precSpace,
                                  final boolean follSpace) {
    // Smiley ":-)"
    if (i >= 2 && tokens[i-2].getToken().equals(":") && tokens[i-1].getToken().equals("-") && tokens[i].getToken().equals(")")) {
      return false;
    }
    // Smiley ";-)"
    return !(i >= 2 && tokens[i - 2].getToken().equals(";") && tokens[i - 1].getToken().equals("-") && tokens[i].getToken().equals(")"));
  }

  @Override
  public final RuleMatch[] match(List<AnalyzedSentence> sentences) {
    UnsyncStack<SymbolLocator> ruleMatchStack = new UnsyncStack<>();
    List<RuleMatch> ruleMatches = new ArrayList<>();
    int startPosBase = 0;
    for (AnalyzedSentence sentence : sentences) {
      AnalyzedTokenReadings[] tokens = sentence.getTokensWithoutWhitespace();
      for (int i = 1; i < tokens.length; i++) {
        for (int j = 0; j < startSymbols.length; j++) {
          if (fillSymbolStack(startPosBase, tokens, i, j)) {
            break;
          }
        }
      }
      for (AnalyzedTokenReadings readings : sentence.getTokens()) {
        startPosBase += readings.getToken().length();
      }
    }
    for (final SymbolLocator sLoc : symbolStack) {
      final RuleMatch rMatch = createMatch(ruleMatches, ruleMatchStack, sLoc.startPos, sLoc.symbol);
      if (rMatch != null) {
        ruleMatches.add(rMatch);
      }
    }
    symbolStack.clear();
    return toRuleMatchArray(ruleMatches);
  }

  private boolean fillSymbolStack(int startPosBase, AnalyzedTokenReadings[] tokens, int i, int j) {
    String token = tokens[i].getToken();
    int startPos = startPosBase + tokens[i].getStartPos();
    if (token.equals(startSymbols[j]) || token.equals(endSymbols[j])) {
      boolean precededByWhitespace = getPrecededByWhitespace(tokens, i, j);
      boolean followedByWhitespace = getFollowedByWhitespace(tokens, i, j);
      boolean noException = isNoException(token, tokens, i, j,
              precededByWhitespace, followedByWhitespace);

      if (noException && precededByWhitespace && token.equals(startSymbols[j])) {
        symbolStack.push(new SymbolLocator(startSymbols[j], i, startPos));
        return true;
      } else if (noException && (followedByWhitespace || tokens[i].isSentenceEnd())
              && token.equals(endSymbols[j])) {
        if (i > 1 && endSymbols[j].equals(")")
                && (numerals.matcher(tokens[i - 1].getToken()).matches()
                && !(!symbolStack.empty()
                && "(".equals(symbolStack.peek().symbol)))) {
        } else {
          if (symbolStack.empty()) {
            symbolStack.push(new SymbolLocator(endSymbols[j], i, startPos));
            return true;
          } else {
            if (symbolStack.peek().symbol.equals(startSymbols[j])) {
              symbolStack.pop();
              return true;
            } else {
              if (isEndSymbolUnique(endSymbols[j])) {
                symbolStack.push(new SymbolLocator(endSymbols[j], i, startPos));
                return true;
              } else {
                if (j == endSymbols.length - 1) {
                  symbolStack.push(new SymbolLocator(endSymbols[j], i, startPos));
                  return true;
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

  private boolean getPrecededByWhitespace(AnalyzedTokenReadings[] tokens, int i, int j) {
    boolean precededByWhitespace = true;
    if (startSymbols[j].equals(endSymbols[j])) {
      precededByWhitespace = tokens[i - 1].isSentenceStart()
          || tokens[i].isWhitespaceBefore()
          || PUNCTUATION_NO_DOT.matcher(tokens[i - 1].getToken()).matches();
    }
    return precededByWhitespace;
  }

  private boolean getFollowedByWhitespace(AnalyzedTokenReadings[] tokens, int i, int j) {
    boolean followedByWhitespace = true;
    if (i < tokens.length - 1 && startSymbols[j].equals(endSymbols[j])) {
      followedByWhitespace = tokens[i + 1].isWhitespaceBefore()
              || PUNCTUATION.matcher(tokens[i + 1].getToken()).matches();
    }
    return followedByWhitespace;
  }

  private boolean isEndSymbolUnique(final String str) {
    return uniqueMap.get(str);
  }

  private RuleMatch createMatch(List<RuleMatch> ruleMatches, UnsyncStack<SymbolLocator> ruleMatchStack, int startPos, String symbol) {
    if (!ruleMatchStack.empty()) {
      final int index = findSymbolNum(symbol);
      if (index >= 0) {
        final SymbolLocator rLoc = ruleMatchStack.peek();
        if (rLoc.symbol.equals(startSymbols[index])) {
          if (ruleMatches.size() > rLoc.index) {
            ruleMatches.remove(rLoc.index);
            ruleMatchStack.pop();
            return null;
          }
        }
      }
    }
    ruleMatchStack.push(new SymbolLocator(symbol, ruleMatches.size(), startPos));
    return new RuleMatch(this, startPos, startPos + symbol.length(), messages.getString("unpaired_brackets"));
  }

  private int findSymbolNum(final String ch) {
    for (int i = 0; i < endSymbols.length; i++) {
      if (ch.equals(endSymbols[i])) {
        return i;
      }
    }
    return -1;
  }

}
