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
 */
public class FileBrowserActivity extends Activity {
    static final String EXTRA_URIS = "io.lslegacy.URIS";

    private final List<File> entries = new ArrayList<File>();
    private final Set<String> selected = new LinkedHashSet<String>();
    private File dir;
    private TextView pathView;
    private Button sendButton;
    private EntryAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        pathView = new TextView(this);
        pathView.setTextSize(13);
        root.addView(pathView);

        ListView list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = entries.get(position);
                if (f.isDirectory()) {
                    show(f);
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
        setTitle("Choose files");

        File start = Environment.getExternalStorageDirectory();
        show(start != null && start.canRead() ? start : new File("/"));
        updateButton();
    }

    @Override public void onBackPressed() {
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
            check.setVisibility(f.isDirectory() ? View.GONE : View.VISIBLE);
            check.setChecked(selected.contains(f.getAbsolutePath()));
            return v;
        }
    }
}
