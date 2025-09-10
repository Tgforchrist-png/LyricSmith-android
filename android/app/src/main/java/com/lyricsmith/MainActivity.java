package com.lyricsmith;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v12: Suggestions moved to header + CMU dictionary-based rhymes.
 * Rhyme rule: match from the last PRIMARY-STRESS vowel (digit '1') to the end of the phoneme sequence.
 * Falls back to a tiny built-in map if the CMU asset is unavailable.
 */
public class MainActivity extends AppCompatActivity {
  private EditText editBody, editProjectName;
  private TextView wordCount, syllableCount, suggestionsBox;
  private Uri currentFileUri = null;

  private static final int CREATE_FILE = 100;
  private static final int OPEN_FILE = 101;

  // CMU dictionary storage: word -> list of pronunciations (each as String[] phonemes)
  private final Map<String, List<String[]>> cmu = new HashMap<>();
  // Rhyme index: rhymeKey -> set of words (dedup, preserve order)
  private final Map<String, Set<String>> rhymeIndex = new HashMap<>();
  private boolean cmuLoaded = false;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    editProjectName = findViewById(R.id.editProjectName);
    editBody        = findViewById(R.id.editBody);
    wordCount       = findViewById(R.id.wordCount);
    syllableCount   = findViewById(R.id.syllableCount);
    suggestionsBox  = findViewById(R.id.suggestionsBox);

    // Restore saved project name
    SharedPreferences prefs = getSharedPreferences("LyricSmith", MODE_PRIVATE);
    editProjectName.setText(prefs.getString("projectName", ""));

    // Load CMU dict (best-effort)
    loadCmuDictionary();

    editBody.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(android.text.Editable s) {
        updateStatsAndSuggestions(s.toString());
      }
    });
  }

  @Override
  protected void onPause() {
    super.onPause();
    SharedPreferences prefs = getSharedPreferences("LyricSmith", MODE_PRIVATE);
    prefs.edit().putString("projectName", editProjectName.getText().toString()).apply();
  }

  // --- Menu ---
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_save:    saveFile(currentFileUri, false); return true;
      case R.id.action_save_as: createFile(); return true;
      case R.id.action_open:    openFile(); return true;
      default: return super.onOptionsItemSelected(item);
    }
  }

  // --- File handling ---
  private void createFile() {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TITLE, "lyrics.txt");
    startActivityForResult(intent, CREATE_FILE);
  }

  private void openFile() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    startActivityForResult(intent, OPEN_FILE);
  }

  private void saveFile(Uri uri, boolean showToast) {
    if (uri == null) { createFile(); return; }
    try {
      OutputStream os = getContentResolver().openOutputStream(uri);
      os.write(editBody.getText().toString().getBytes());
      os.close();
      if (showToast) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    } catch (IOException e) {
      e.printStackTrace();
      Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK || data == null) return;
    Uri uri = data.getData();
    if (requestCode == CREATE_FILE) {
      currentFileUri = uri;
      saveFile(uri, true);
    } else if (requestCode == OPEN_FILE) {
      currentFileUri = uri;
      try {
        InputStream is = getContentResolver().openInputStream(uri);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        editBody.setText(sb.toString());
      } catch (IOException e) {
        e.printStackTrace();
        Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show();
      }
    }
  }

  // --- Stats + Rhymes ---
  private void updateStatsAndSuggestions(String text) {
    wordCount.setText("Words: " + countWords(text));
    String last = lastNonEmptyLine(text);
    syllableCount.setText("Syllables (line): " + countSyllables(last));
    String target = lastWord(last);
    if (target.isEmpty()) {
      suggestionsBox.setText("(start typing…)");
    } else {
      List<String> sugg = cmuLoaded ? cmuRhymes(target, 24) : naiveRhymes(target);
      if (sugg.isEmpty()) {
        suggestionsBox.setText("(no matches — try another word)");
      } else {
        suggestionsBox.setText(joinBullets(sugg));
      }
    }
  }

  private static String joinBullets(List<String> items) {
    StringBuilder sb = new StringBuilder();
    for (int i=0; i<items.size(); i++) {
      if (i>0) sb.append(" · ");
      sb.append(items.get(i));
    }
    return sb.toString();
  }

  private static int countWords(String text) {
    String t = text.trim();
    if (t.isEmpty()) return 0;
    return t.split("\\s+").length;
  }

  private static String lastNonEmptyLine(String text) {
    String[] lines = text.split("\\R");
    for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].trim().isEmpty()) return lines[i];
    return "";
  }

  private static String lastWord(String line) {
    String t = line.trim();
    if (t.isEmpty()) return "";
    String[] parts = t.split("\\s+");
    return parts[parts.length - 1].replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "").toLowerCase(Locale.US);
  }

  private static int countSyllables(String line) {
    if (line == null || line.trim().isEmpty()) return 0;
    int total = 0;
    for (String w : line.toLowerCase(Locale.US).split("\\s+")) total += Math.max(syllablesInWord(w),1);
    return total;
  }

  private static int syllablesInWord(String w) {
    w = w.replaceAll("[^a-z]", "");
    if (w.isEmpty()) return 0;
    int groups = w.replaceAll("y","i").split("(?i:[^aeiou]+)", -1).length-1;
    if (w.endsWith("e") && groups>1) groups--;
    return Math.max(groups,1);
  }

  // ---- CMU dictionary loading and rhyme logic ----
  private void loadCmuDictionary() {
    try {
      AssetManager am = getAssets();
      InputStream is = am.open("cmudict.txt"); // placed in assets earlier
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isEmpty() || line.charAt(0) == ';') continue; // skip comments
        // Format: WORD  PHONEMES...
        int sp = line.indexOf("  ");
        if (sp <= 0) continue;
        String head = line.substring(0, sp).trim();      // WORD or WORD(1)
        String phones = line.substring(sp).trim();       // ARPAbet tokens
        String base = head.replaceAll("\\(\\d+\\)$", "").toLowerCase(Locale.US);
        String[] toks = phones.split("\\s+");
        if (toks.length == 0) continue;
        cmu.computeIfAbsent(base, k -> new ArrayList<>()).add(toks);
      }
      br.close();

      // Build rhyme index: key(from last primary stress) -> set of words
      for (Map.Entry<String, List<String[]>> e : cmu.entrySet()) {
        String word = e.getKey();
        for (String[] ph : e.getValue()) {
          String key = rhymeKey(ph);
          if (key == null) continue;
          rhymeIndex.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(word);
        }
      }
      cmuLoaded = true;
    } catch (Throwable t) {
      // Fallback: tiny internal list; still better than nothing
      cmu.clear();
      rhymeIndex.clear();
      seedTinyFallback();
      cmuLoaded = !rhymeIndex.isEmpty();
    }
  }

  private static String rhymeKey(String[] phones) {
    // Find last phoneme that has primary stress '1' (e.g., AH1, AO1, EY1)
    int idx = -1;
    for (int i = phones.length - 1; i >= 0; i--) {
      if (phones[i].matches(".*1$")) { idx = i; break; }
    }
    if (idx < 0) return null;
    // Build key from that index to end, drop stress digits for robustness
    StringBuilder sb = new StringBuilder();
    for (int i = idx; i < phones.length; i++) {
      sb.append(phones[i].replaceAll("\\d","")).append("_");
    }
    return sb.toString();
  }

  private List<String> cmuRhymes(String w, int limit) {
    String key = null;
    List<String[]> prons = cmu.get(w.toLowerCase(Locale.US));
    if (prons != null) {
      // Prefer first pronunciation that yields a key
      for (String[] ph : prons) { key = rhymeKey(ph); if (key != null) break; }
    }
    if (key == null) return new ArrayList<>();

    Set<String> bucket = rhymeIndex.getOrDefault(key, new LinkedHashSet<>());
    List<String> out = new ArrayList<>();
    for (String cand : bucket) {
      if (out.size() >= limit) break;
      if (!cand.equalsIgnoreCase(w)) out.add(cand);
    }
    return out;
  }

  private void seedTinyFallback() {
    // A few examples to demonstrate correctness without the full asset
    // door -> more, floor, core, pour, sore
    addPron("door", new String[]{"D","AO1","R"});
    addPron("more", new String[]{"M","AO1","R"});
    addPron("floor", new String[]{"F","L","AO1","R"});
    addPron("core", new String[]{"K","AO1","R"});
    addPron("pour", new String[]{"P","AO1","R"});
    addPron("sore", new String[]{"S","AO1","R"});

    // time -> rhyme, prime, climb, slime
    addPron("time", new String[]{"T","AY1","M"});
    addPron("rhyme", new String[]{"R","AY1","M"});
    addPron("prime", new String[]{"P","R","AY1","M"});
    addPron("climb", new String[]{"K","L","AY1","M"});
    addPron("slime", new String[]{"S","L","AY1","M"});

    // light -> night, sight, write
    addPron("light", new String[]{"L","AY1","T"});
    addPron("night", new String[]{"N","AY1","T"});
    addPron("sight", new String[]{"S","AY1","T"});
    addPron("write", new String[]{"R","AY1","T"});

    // Build index
    for (Map.Entry<String, List<String[]>> e : cmu.entrySet()) {
      String word = e.getKey();
      for (String[] ph : e.getValue()) {
        String key = rhymeKey(ph);
        if (key == null) continue;
        rhymeIndex.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(word);
      }
    }
  }

  private void addPron(String word, String[] phones) {
    cmu.computeIfAbsent(word, k -> new ArrayList<>()).add(phones);
  }

  // Previous naive fallback (kept in case both CMU + tiny fallback fail)
  private static List<String> naiveRhymes(String w) {
    if (w.length()<2) return Arrays.asList();
    String tail = w.substring(Math.max(0, w.length()-3));
    return Arrays.asList(tail+"ore", tail+"orey", tail+"orez", tail+"orex");
  }
}
