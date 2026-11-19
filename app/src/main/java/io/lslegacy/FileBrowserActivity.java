package io.lslegacy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A minimal multi-select file browser. Stock Android 2.x usually has no app that answers
 * ACTION_GET_CONTENT for arbitrary files, so this is the fallback (and works everywhere).
 * Returns the chosen files as file:// URIs in {@link #EXTRA_URIS}.
 *
 * <p>Started with {@link #EXTRA_BROWSE} it is a plain file manager instead: tapping a file opens it.
 * The history uses this for "Open folder" and "Show in folder".
 */
public class FileBrowserActivity extends Activity {
    static final String EXTRA_URIS = "io.lslegacy.URIS";
    /** Folder to browse (opening files rather than picking them). */
    static final String EXTRA_BROWSE = "io.lslegacy.BROWSE";
    /** Name of a file in that folder to scroll to and mark. */
    static final String EXTRA_SELECT = "io.lslegacy.SELECT";

    private final List<File> entries = new ArrayList<File>();
    private final Set<String> selected = new LinkedHashSet<String>();
    private File dir;
    private TextView pathView;
    private Button sendButton;
    private EntryAdapter adapter;
    private ListView list;
    private boolean browsing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        pathView = new TextView(this);
        pathView.setTextSize(13);
        root.addView(pathView);

        browsing = getIntent().getStringExtra(EXTRA_BROWSE) != null;
        list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = entries.get(position);
                if (f.isDirectory()) {
                    show(f);
                } else if (browsing) {
                    Opener.open(FileBrowserActivity.this, f, null, null);
                } else {
                    String p = f.getAbsolutePath();
                    if (!selected.remove(p)) selected.add(p);
                    adapter.notifyDataSetChanged();
                    updateButton();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        sendButton = new Button(this);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                for (String p : selected) uris.add(Uri.fromFile(new File(p)));
                setResult(RESULT_OK, new Intent().putParcelableArrayListExtra(EXTRA_URIS, uris));
                finish();
            }
        });
        root.addView(sendButton);
        setContentView(root);

        if (browsing) {
            setTitle("Files");
            sendButton.setVisibility(View.GONE);
            File dir = new File(getIntent().getStringExtra(EXTRA_BROWSE));
            dir.mkdirs();
            show(dir.canRead() ? dir : new File("/"));
            mark(getIntent().getStringExtra(EXTRA_SELECT));
            return;
        }
        setTitle("Choose files");
        File start = Environment.getExternalStorageDirectory();
        show(start != null && start.canRead() ? start : new File("/"));
        updateButton();
    }

    @Override public void onBackPressed() {
        // Opened from the history: Back leaves sub-folders, then returns to the history.
        if (browsing && dir.getAbsolutePath().equals(getIntent().getStringExtra(EXTRA_BROWSE))) {
            super.onBackPressed();
            return;
        }
        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) show(parent);
        else super.onBackPressed();
    }

    private void show(File d) {
        dir = d;
        pathView.setText(d.getAbsolutePath());
        entries.clear();
        File[] files = d.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                if (!f.getName().startsWith(".") && f.canRead()) entries.add(f);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** Scrolls to a file and ticks it; touch mode would hide an ordinary list selection. */
    private void mark(String name) {
        if (name == null) return;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getName().equals(name)) {
                selected.add(entries.get(i).getAbsolutePath());
                adapter.notifyDataSetChanged();
                final int pos = i;
                list.post(new Runnable() {
                    @Override public void run() {
                        list.setSelection(pos);
                    }
                });
                return;
            }
        }
    }

    private void updateButton() {
        int n = selected.size();
        sendButton.setEnabled(n > 0);
        sendButton.setText(n == 0 ? "Tap files to select them" : "Send " + n + (n == 1 ? " file" : " files"));
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() {
            return entries.size();
        }

        @Override public Object getItem(int position) {
            return entries.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            File f = entries.get(position);
            View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_file, parent, false);
            CheckBox check = (CheckBox) v.findViewById(R.id.check);
            ((TextView) v.findViewById(R.id.name)).setText(f.isDirectory() ? f.getName() + "/" : f.getName());
            ((TextView) v.findViewById(R.id.detail)).setText(f.isDirectory() ? "Folder" : Util.formatSize(f.length()));
            boolean ticked = selected.contains(f.getAbsolutePath());
            check.setVisibility(f.isDirectory() || (browsing && !ticked) ? View.GONE : View.VISIBLE);
            check.setChecked(selected.contains(f.getAbsolutePath()));
            return v;
        }
    }
}
