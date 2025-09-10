package com.lyricsmith;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.text.TextPaint;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
  private EditText editBody, editProjectName;
  private TextView wordCount, syllableCount, suggestionsBox;
  private Spinner spinnerRhymeType;
  private Uri currentFileUri = null;

  private static final int CREATE_FILE = 100;
  private static final int OPEN_FILE = 101;

  enum RhymeMode { EXACT, COMPOUND, CONSONANT_SLOP, ASSONANCE, PHRASES }
  private RhymeMode currentMode = RhymeMode.EXACT;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    editProjectName = findViewById(R.id.editProjectName);
    editBody = findViewById(R.id.editBody);
    wordCount = findViewById(R.id.wordCount);
    syllableCount = findViewById(R.id.syllableCount);
    suggestionsBox = findViewById(R.id.suggestionsBox);
    spinnerRhymeType = findViewById(R.id.spinnerRhymeType);

    ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
            this, R.array.rhyme_modes, android.R.layout.simple_spinner_item);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinnerRhymeType.setAdapter(adapter);
    spinnerRhymeType.setSelection(0);
    spinnerRhymeType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        currentMode = RhymeMode.values()[position];
        updateStatsAndSuggestions(editBody.getText().toString());
      }
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });

    SharedPreferences prefs = getSharedPreferences("LyricSmith", MODE_PRIVATE);
    editProjectName.setText(prefs.getString("projectName", ""));

    editBody.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(android.text.Editable s) {
        updateStatsAndSuggestions(s.toString());
      }
    });

    suggestionsBox.setMovementMethod(LinkMovementMethod.getInstance());
  }

  @Override
  protected void onPause() {
    super.onPause();
    SharedPreferences prefs = getSharedPreferences("LyricSmith", MODE_PRIVATE);
    prefs.edit().putString("projectName", editProjectName.getText().toString()).apply();
  }

  // Menu
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_main, menu);
    return true;
  }
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_save)    { saveFile(currentFileUri, false); return true; }
    if (id == R.id.action_save_as) { createFile(); return true; }
    if (id == R.id.action_open)    { openFile(); return true; }
    return super.onOptionsItemSelected(item);
  }

  // File handling
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
    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
      if (os == null) throw new IOException("No stream");
      os.write(editBody.getText().toString().getBytes());
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
      try (InputStream is = getContentResolver().openInputStream(uri);
           BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        editBody.setText(sb.toString());
      } catch (IOException e) {
        e.printStackTrace();
        Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show();
      }
    }
  }

  // Stats + suggestions
  private void updateStatsAndSuggestions(String text) {
    wordCount.setText("Words: " + countWords(text));
    String last = lastNonEmptyLine(text);
    syllableCount.setText("Syllables (line): " + countSyllables(last));
    String target = lastWord(last);

    List<String> suggestions;
    switch (currentMode) {
      case EXACT: suggestions = exactRhymes(target, 20); break;
      case COMPOUND: suggestions = compoundRhymes(target, 20); break;
      case CONSONANT_SLOP: suggestions = consonantSlop(target, 20); break;
      case ASSONANCE: suggestions = assonanceRhymes(target, 20); break;
      case PHRASES: suggestions = relevantPhrases(target, 20); break;
      default: suggestions = Collections.emptyList();
    }
    renderClickableSuggestions(suggestions, target);
  }

  private void renderClickableSuggestions(List<String> items, String target) {
    if (target == null) target = "";
    if (items == null || items.isEmpty() || target.isEmpty()) {
      suggestionsBox.setText("No matches — try another word");
      return;
    }
    SpannableStringBuilder sb = new SpannableStringBuilder();
    for (int i=0;i<items.size();i++) {
      String token = items.get(i);
      int start = sb.length();
      sb.append(token);
      int end = sb.length();

      final String wordOrPhrase = token;
      sb.setSpan(new UnderlineSpan(), start, end, 0);
      sb.setSpan(new ClickableSpan() {
        @Override public void onClick(View widget) {
          if (currentMode == RhymeMode.PHRASES) {
            insertAtCursor(wordOrPhrase);
          } else {
            showDictionarySheet(wordOrPhrase);
          }
        }
        @Override public void updateDrawState(TextPaint ds) {
          super.updateDrawState(ds);
          ds.setUnderlineText(true);
        }
      }, start, end, 0);

      if (i < items.size()-1) sb.append(" · ");
    }
    suggestionsBox.setText(sb);
  }

  private void insertAtCursor(String phrase) {
    if (phrase == null || phrase.trim().isEmpty()) return;
    int start = Math.max(editBody.getSelectionStart(), 0);
    int end = Math.max(editBody.getSelectionEnd(), 0);
    editBody.getText().replace(Math.min(start, end), Math.max(start, end), phrase);
  }

  // Dictionary popup (BottomSheet with 3 tabs)
  private void showDictionarySheet(String word) {
    BottomSheetDialog sheet = new BottomSheetDialog(this);
    View view = getLayoutInflater().inflate(R.layout.bottomsheet_dict, null);
    TextView dictWord = view.findViewById(R.id.dictWord);
    TabLayout tabs = view.findViewById(R.id.dictTabs);
    TextView content = view.findViewById(R.id.dictContent);

    dictWord.setText(word);
    tabs.addTab(tabs.newTab().setText("Definition"));
    tabs.addTab(tabs.newTab().setText("Synonyms"));
    tabs.addTab(tabs.newTab().setText("Antonyms"));

    new AsyncTask<Void, Void, Map<String, List<String>>>() {
      @Override protected Map<String, List<String>> doInBackground(Void... voids) {
        Map<String, List<String>> out = new HashMap<>();
        out.put("def", new ArrayList<>());
        out.put("syn", new ArrayList<>());
        out.put("ant", new ArrayList<>());
        try {
          URL url = new URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + word);
          HttpURLConnection conn = (HttpURLConnection) url.openConnection();
          conn.setConnectTimeout(6000);
          conn.setReadTimeout(6000);
          try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder(); String line;
            while((line = br.readLine())!=null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i=0;i<arr.length();i++) {
              JSONObject entry = arr.getJSONObject(i);
              JSONArray meanings = entry.optJSONArray("meanings");
              if (meanings == null) continue;
              for (int j=0;j<meanings.length();j++) {
                JSONObject m = meanings.getJSONObject(j);
                JSONArray defs = m.optJSONArray("definitions");
                if (defs != null) {
                  for (int k=0;k<defs.length();k++) {
                    JSONObject d = defs.getJSONObject(k);
                    String def = d.optString("definition", "");
                    if (!def.isEmpty()) out.get("def").add(def);
                    JSONArray syn = d.optJSONArray("synonyms");
                    if (syn != null) for (int s=0;s<syn.length();s++) out.get("syn").add(syn.getString(s));
                    JSONArray ant = d.optJSONArray("antonyms");
                    if (ant != null) for (int a=0;a<ant.length();a++) out.get("ant").add(ant.getString(a));
                  }
                }
              }
            }
          }
        } catch (Exception ignored) { }
        return out;
      }
      @Override protected void onPostExecute(Map<String, List<String>> data) {
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
          @Override public void onTabSelected(TabLayout.Tab tab) { applyTab(tab.getPosition()); }
          @Override public void onTabUnselected(TabLayout.Tab tab) {}
          @Override public void onTabReselected(TabLayout.Tab tab) { applyTab(tab.getPosition()); }
          private void applyTab(int pos) {
            List<String> vals = pos==0? data.get("def") : pos==1? data.get("syn") : data.get("ant");
            if (vals==null || vals.isEmpty()) {
              content.setText(pos==0? "No definition found." : pos==1? "No synonyms found." : "No antonyms found.");
            } else {
              StringBuilder b = new StringBuilder();
              int count = 0;
              LinkedHashSet<String> uniq = new LinkedHashSet<>(vals);
              for (String s: uniq) {
                b.append("• ").append(s).append("\n");
                if (++count>=30) break;
              }
              content.setText(b.toString().trim());
            }
          }
        });
        TabLayout.Tab first = tabs.getTabAt(0);
        if (first!=null) first.select();
      }
    }.execute();

    sheet.setContentView(view);
    sheet.show();
  }

  // Basic helpers
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
    String t = line == null ? "" : line.trim();
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

  // Rhyme engines (simple placeholders)
  private static List<String> exactRhymes(String w, int limit) {
    if (w==null || w.length()<2) return Collections.emptyList();
    String[] bank = {"more","core","bore","pour","sore","shore","floor","score","adore","before","restore","outdoor"};
    return filterByEnding(w, bank, true, limit);
  }
  private static List<String> compoundRhymes(String w, int limit) {
    if (w==null || w.length()<2) return Collections.emptyList();
    String[] bank = {"sliding door","hardcore","either/or","backdoor","encore","sycamore","dinomore"};
    return filterByEnding(w, bank, true, limit);
  }
  private static List<String> consonantSlop(String w, int limit) {
    if (w==null || w.length()<2) return Collections.emptyList();
    String[] bank = {"tire","dare","tear","fire","fir","for","four","fur"}; // loose on consonants
    return filterByEnding(w, bank, false, limit);
  }
  private static List<String> assonanceRhymes(String w, int limit) {
    if (w==null || w.isEmpty()) return Collections.emptyList();
    String vowel = w.replaceAll("(?i)[^aeiou]", "");
    String[] bank = {"more","flow","tone","home","road","roar","told","torn","toward"};
    List<String> out = new ArrayList<>();
    for (String cand: bank) {
      String v = cand.replaceAll("(?i)[^aeiou]", "");
      if (v.equals(vowel)) out.add(cand);
      if (out.size()>=limit) break;
    }
    return out;
  }
  private static List<String> relevantPhrases(String w, int limit) {
    if (w==null || w.isEmpty()) return Arrays.asList("open the door","behind closed doors","door to door","shut the front door","backdoor deal","keep the score");
    return Arrays.asList("open the " + w, "behind closed " + w + "s", "at your " + w, "next to the " + w);
  }
  private static List<String> filterByEnding(String target, String[] bank, boolean strict, int limit) {
    List<String> out = new ArrayList<>();
    String t = target.toLowerCase(Locale.US);
    for (String s: bank) {
      String c = s.toLowerCase(Locale.US);
      boolean ok = c.endsWith(t);
      if (!strict && !ok) ok = c.matches(".*" + Pattern.quote(t.substring(Math.max(0,t.length()-2))) + ".*");
      if (ok) out.add(s);
      if (out.size()>=limit) break;
    }
    return out;
  }
}
