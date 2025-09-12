package com.lyricsmith;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public class MainActivity extends AppCompatActivity {
  private EditText editBody, editProjectName, editActive;
  private TextView wordCount, syllableCount, suggestionsBox;

  private Button btnClear, btnRecall, btnViewList, btnApply;

  // File I/O
  private Uri currentFileUri = null;
  private static final int CREATE_FILE = 100;
  private static final int OPEN_FILE = 101;

  // Queue storage (top=head, bottom=tail)
  private final Deque<String> queue = new ArrayDeque<>();
  private static final String PREFS = "LyricSmith";
  private static final String KEY_PROJECT = "projectName";
  private static final String KEY_QUEUE = "activeQueue";
  private static final String SEP = "\u0001"; // non-printing separator

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // Bind views
    editProjectName = findViewById(R.id.editProjectName);
    editActive      = findViewById(R.id.editActive);
    editBody        = findViewById(R.id.editBody);
    wordCount       = findViewById(R.id.wordCount);
    syllableCount   = findViewById(R.id.syllableCount);
    suggestionsBox  = findViewById(R.id.suggestionsBox);

    btnClear    = findViewById(R.id.btnClear);
    btnRecall   = findViewById(R.id.btnRecall);
    btnViewList = findViewById(R.id.btnViewList);
    btnApply    = findViewById(R.id.btnApply);

    // Restore project name + queue
    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    editProjectName.setText(prefs.getString(KEY_PROJECT, ""));
    restoreQueue(prefs.getString(KEY_QUEUE, ""));

    // Live stats / suggestions from body
    editBody.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(android.text.Editable s) {
        updateStatsAndSuggestions(s.toString());
      }
    });

    // --- Button wiring ---

    // Clear: push active -> TOP of queue (if non-blank), then clear active
    btnClear.setOnClickListener(v -> {
      String t = trimOrEmpty(editActive.getText().toString());
      if (!t.isEmpty()) {
        queue.addFirst(t); // top
        persistQueue();
        Toast.makeText(this, "Saved to top of list", Toast.LENGTH_SHORT).show();
      }
      editActive.setText("");
    });

    // Recall: if active not blank, append it to BOTTOM; then pop TOP into active
    btnRecall.setOnClickListener(v -> {
      String active = trimOrEmpty(editActive.getText().toString());
      if (!active.isEmpty()) {
        queue.addLast(active); // bottom
      }
      String next = queue.pollFirst(); // from top
      if (next == null) {
        Toast.makeText(this, "List is empty", Toast.LENGTH_SHORT).show();
      } else {
        editActive.setText(next);
      }
      persistQueue();
    });

    // View List: show top->bottom
    btnViewList.setOnClickListener(v -> {
      List<String> items = new ArrayList<>(queue);
      if (items.isEmpty()) items.add("(empty)");
      CharSequence[] arr = items.toArray(new CharSequence[0]);
      new AlertDialog.Builder(this)
          .setTitle("Queue (top → bottom)")
          .setItems(arr, null)
          .setPositiveButton("Close", null)
          .show();
    });

    // Apply: append active to body (next available line), then clear active
    btnApply.setOnClickListener(v -> {
      String t = trimOrEmpty(editActive.getText().toString());
      if (t.isEmpty()) {
        Toast.makeText(this, "Nothing to apply", Toast.LENGTH_SHORT).show();
        return;
      }
      String body = editBody.getText().toString();
      if (body.isEmpty() || body.endsWith("\n")) {
        editBody.append(t);
      } else {
        editBody.append("\n" + t);
      }
      editActive.setText("");
    });

    // Initial stats render
    updateStatsAndSuggestions(editBody.getText().toString());
  }

  @Override
  protected void onPause() {
    super.onPause();
    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    prefs.edit()
        .putString(KEY_PROJECT, editProjectName.getText().toString())
        .putString(KEY_QUEUE, dumpQueue())
        .apply();
  }

  // ===== Menu (Save / Save As / Open) =====
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
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        editBody.setText(sb.toString());
      } catch (IOException e) {
        e.printStackTrace();
        Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show();
      }
    }
  }

  // ===== Stats + naive rhymes =====
  private void updateStatsAndSuggestions(String text) {
    wordCount.setText("Words: " + countWords(text));
    String last = lastNonEmptyLine(text);
    syllableCount.setText("Syllables (line): " + countSyllables(last));
    String target = lastWord(last);
    suggestionsBox.setText(target.isEmpty() ? "(start typing…)" : String.join(" · ", naiveRhymes(target)));
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
    return parts[parts.length - 1].replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "").toLowerCase();
  }

  private static int countSyllables(String line) {
    if (line == null || line.trim().isEmpty()) return 0;
    int total = 0;
    for (String w : line.toLowerCase().split("\\s+")) total += Math.max(syllablesInWord(w),1);
    return total;
  }

  private static int syllablesInWord(String w) {
    w = w.replaceAll("[^a-z]", "");
    if (w.isEmpty()) return 0;
    int groups = w.replaceAll("y","i").split("(?i:[^aeiou]+)", -1).length-1;
    if (w.endsWith("e") && groups>1) groups--;
    return Math.max(groups,1);
  }

  private static List<String> naiveRhymes(String w) {
    if (w.length()<2) return Arrays.asList();
    String tail = w.substring(Math.max(0, w.length()-3));
    return Arrays.asList(tail+"ay", tail+"en", tail+"end", tail+"in", tail+"ing");
  }

  // ===== Queue helpers =====
  private void restoreQueue(String packed) {
    queue.clear();
    if (packed == null || packed.isEmpty()) return;
    String[] parts = packed.split(SEP, -1);
    for (String p : parts) {
      if (!p.isEmpty()) queue.addLast(p);
    }
  }

  private String dumpQueue() {
    if (queue.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String s : queue) {
      if (!first) sb.append(SEP);
      sb.append(s);
      first = false;
    }
    return sb.toString();
  }

  private void persistQueue() {
    getSharedPreferences(PREFS, MODE_PRIVATE)
        .edit()
        .putString(KEY_QUEUE, dumpQueue())
        .apply();
  }

  private static String trimOrEmpty(String s) {
    return s == null ? "" : s.trim();
  }
}
