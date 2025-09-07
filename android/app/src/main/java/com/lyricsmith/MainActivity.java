package com.lyricsmith;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
  private EditText editBody, editProjectName;
  private TextView wordCount, syllableCount, suggestionsBox;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    editProjectName = findViewById(R.id.editProjectName);
    editBody = findViewById(R.id.editBody);
    wordCount = findViewById(R.id.wordCount);
    syllableCount = findViewById(R.id.syllableCount);
    suggestionsBox = findViewById(R.id.suggestionsBox);

    editBody.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) {
        updateStatsAndSuggestions(s.toString());
      }
    });
  }

  private void updateStatsAndSuggestions(String text) {
    int words = countWords(text);
    wordCount.setText("Words: " + words);

    String last = lastNonEmptyLine(text);
    int syl = countSyllables(last);
    syllableCount.setText("Syllables (line): " + syl);

    String target = lastWord(last);
    if (target.isEmpty()) {
      suggestionsBox.setText("(start typing to see suggestions)");
    } else {
      List<String> rhymes = naiveRhymes(target);
      suggestionsBox.setText(String.join(" · ", rhymes));
    }
  }

  private static int countWords(String text) {
    String t = text.trim();
    if (t.isEmpty()) return 0;
    return t.split("\\s+").length;
  }

  private static String lastNonEmptyLine(String text) {
    String[] lines = text.split("\\R");
    for (int i = lines.length - 1; i >= 0; i--) {
      if (!lines[i].trim().isEmpty()) return lines[i];
    }
    return "";
  }

  private static String lastWord(String line) {
    String t = line.trim();
    if (t.isEmpty()) return "";
    String[] parts = t.split("\\s+");
    String w = parts[parts.length - 1];
    return w.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "").toLowerCase();
  }

  private static int countSyllables(String line) {
    if (line == null || line.trim().isEmpty()) return 0;
    String[] words = line.toLowerCase().split("\\s+");
    int total = 0;
    for (String w : words) {
      int s = syllablesInWord(w);
      total += Math.max(s, 1);
    }
    return total;
  }

  private static int syllablesInWord(String w) {
    if (w.isEmpty()) return 0;
    w = w.replaceAll("[^a-z]", "");
    if (w.isEmpty()) return 0;
    int groups = w.replaceAll("y", "i").split("(?i:[^aeiou]+)", -1).length - 1;
    if (w.endsWith("e") && groups > 1) groups--;
    return Math.max(groups, 1);
  }

  private static List<String> naiveRhymes(String w) {
    if (w.length() < 2) return Arrays.asList();
    String tail = w.substring(Math.max(0, w.length() - 3));
    return Arrays.asList(tail + "ay", tail + "en", tail + "end", tail + "in", tail + "ing");
  }
}
