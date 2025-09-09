package com.lyricsmith;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextWatcher;
import android.text.Editable;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

  private EditText editBody, editProjectName;
  private TextView wordCount, syllableCount, suggestionsBox;
  private Uri currentFileUri = null;

  private static final int CREATE_FILE = 100;
  private static final int OPEN_FILE = 101;

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

    // Live stats + suggestions
    editBody.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) {
        updateStatsAndSuggestions(s.toString());
      }
    });

    // Initial update
    updateStatsAndSuggestions(editBody.getText().toString());
  }

  @Override
  protected void onPause() {
    super.onPause();
    SharedPreferences prefs = getSharedPreferences("LyricSmith", MODE_PRIVATE);
    prefs.edit().putString("projectName", editProjectName.getText().toString()).apply();
  }

  // ----- Menu -----
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_save) { saveFile(currentFileUri, true); return true; }
    if (id == R.id.action_save_as) { createFile(); return true; }
    if (id == R.id.action_open) { openFile(); return true; }
    return super.onOptionsItemSelected(item);
  }

  // ----- File handling -----
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
      if (os == null) throw new IOException("OpenOutputStream returned null");
      os.write(editBody.getText().toString().getBytes());
      os.close();
      if (showToast) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    } catch (IOException e) {
      e.printStackTrace();
      Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK || data == null) return;

    Uri uri = data.getData();
    if (uri == null) return;

    if (requestCode == CREATE_FILE) {
      currentFileUri = uri;
      saveFile(uri, true);
    } else if (requestCode == OPEN_FILE) {
      currentFileUri = uri;
      try {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("OpenInputStream returned null");
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        editBody.setText(sb.toString());
      } catch (IOException e) {
        e.printStackTrace();
        Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show();
      }
    }
  }

  // ----- Stats + Rhymes -----
  private void updateStatsAndSuggestions(String text) {
    wordCount.setText("Words: " + countWords(text));
    String last = lastNonEmptyLine(text);
    syllableCount.setText("Syllables (line): " + countSyllables(last));
    String target = lastWord(last);
    List<String> sugg = target.isEmpty() ? Collections.emptyList() : naiveRhymes(target);
    suggestionsBox.setText(sugg.isEmpty() ? "(start typing…)" : String.join(" · ", sugg));
  }

  private static int countWords(String text) {
    String t = text == null ? "" : text.trim();
    if (t.isEmpty()) return 0;
    return t.split("\\s+").length;
  }

  private static String lastNonEmptyLine(String text) {
    if (text == null) return "";
    String[] lines = text.split("\\R");
    for (int i = lines.length - 1; i >= 0; i--) {
      if (!lines[i].trim().isEmpty()) return lines[i];
    }
    return "";
  }

  private static String lastWord(String line) {
    String t = line == null ? "" : line.trim();
    if (t.isEmpty()) return "";
    String[] parts = t.split("\\s+");
    String w = parts[parts.length - 1];
    return w.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "").toLowerCase();
  }

  private static int countSyllables(String line) {
    if (line == null || line.trim().isEmpty()) return 0;
    int total = 0;
    for (String w : line.toLowerCase().split("\\s+")) total += Math.max(syllablesInWord(w), 1);
    return total;
  }

  private static int syllablesInWord(String w) {
    w = w.replaceAll("[^a-z]", "");
    if (w.isEmpty()) return 0;
    int groups = w.replaceAll("y", "i").split("(?i:[^aeiou]+)", -1).length - 1;
    if (w.endsWith("e") && groups > 1) groups--;
    return Math.max(groups, 1);
  }

  private static List<String> naiveRhymes(String w) {
    if (w == null || w.length() < 2) return Collections.emptyList();
    String tail = w.substring(Math.max(0, w.length() - 3));
    return Arrays.asList(tail + "ay", tail + "en", tail + "end", tail + "in", tail + "ing");
  }
}
