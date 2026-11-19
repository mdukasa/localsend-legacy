package io.lslegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * LocalSend's receive history: the last 30 files and messages received, newest first. Tap one to open it;
 * long-press for Open file, Show in folder, Information and Delete from history. Phones without a file
 * manager get to their received files here, and "Open folder" browses Download/LocalSend.
 */
public class HistoryActivity extends Activity {
    private static final int OPEN = 1, SHOW_IN_FOLDER = 2, INFO = 3, DELETE = 4;

    private final List<History.Entry> entries = new ArrayList<History.Entry>();
    private EntryAdapter adapter;
    private Button clearButton;
    private TextView hint;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("History");
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (8 * dp);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout buttons = new LinearLayout(this);
        Button openFolder = new Button(this);
        openFolder.setText("Open folder");
        openFolder.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    new AlertDialog.Builder(HistoryActivity.this)
                            .setTitle("No SD card")
                            .setMessage("Received files are saved to the SD card, and there isn't one (or a computer is using it over USB).")
                            .setNegativeButton("Close", null)
                            .show();
                    return;
                }
                browse(Receiver.saveDir(), null);
            }
        });
        clearButton = new Button(this);
        clearButton.setText("Delete history");
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmClear();
            }
        });
        buttons.addView(openFolder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(clearButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        hint = new TextView(this);
        hint.setText("Tap to open. Long-press for more.");
        hint.setTextSize(13);
        hint.setPadding(pad / 2, pad / 2, 0, pad / 2);
        root.addView(hint);

        FrameLayout frame = new FrameLayout(this);
        ListView list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(entries.get(position));
            }
        });
        registerForContextMenu(list);
        TextView empty = new TextView(this);
        empty.setText("The history is empty.");
        empty.setTextSize(18);
        empty.setGravity(Gravity.CENTER);
        list.setEmptyView(empty);
        frame.addView(list);
        frame.addView(empty);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        History.setListener(reload);
        reload.run();
    }

    @Override protected void onPause() {
        History.setListener(null);
        super.onPause();
    }

    private final Runnable reload = new Runnable() {
        @Override public void run() {
            entries.clear();
            entries.addAll(History.entries(HistoryActivity.this));
            adapter.notifyDataSetChanged();
            clearButton.setEnabled(!entries.isEmpty());
            hint.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        }
    };

    private void open(final History.Entry e) {
        if (e.message) {
            Opener.showMessage(this, e.sender, e.name);
            return;
        }
        Opener.open(this, e.file(), e.type, new Runnable() {
            @Override public void run() {
                History.remove(HistoryActivity.this, e.id);
            }
        });
    }

    /** Our own file browser, opened on {@code dir} with {@code select} scrolled to (a stock 2.x phone has no file manager). */
    private void browse(File dir, String select) {
        startActivity(new Intent(this, FileBrowserActivity.class)
                .putExtra(FileBrowserActivity.EXTRA_BROWSE, dir.getAbsolutePath())
                .putExtra(FileBrowserActivity.EXTRA_SELECT, select));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear history")
                .setMessage("Do you really want to delete the entire history?\n\nThe received files stay where they are.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        History.clear(HistoryActivity.this);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
        History.Entry e = entries.get(((AdapterView.AdapterContextMenuInfo) info).position);
        menu.setHeaderTitle(e.message ? "Message from " + e.sender : e.name);
        if (!e.message) {
            menu.add(0, OPEN, 0, "Open file");
            menu.add(0, SHOW_IN_FOLDER, 0, "Show in folder");
        }
        menu.add(0, INFO, 0, "Information");
        menu.add(0, DELETE, 0, "Delete from history");
    }

    @Override public boolean onContextItemSelected(MenuItem item) {
        int pos = ((AdapterView.AdapterContextMenuInfo) item.getMenuInfo()).position;
        if (pos >= entries.size()) return true;
        History.Entry e = entries.get(pos);
        switch (item.getItemId()) {
            case OPEN:
                open(e);
                return true;
            case SHOW_IN_FOLDER:
                File f = e.file();
                browse(f.getParentFile(), f.getName());
                return true;
            case INFO:
                showInfo(e);
                return true;
            case DELETE:
                History.remove(this, e.id);
                return true;
        }
        return super.onContextItemSelected(item);
    }

    private void showInfo(final History.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (!e.message) {
            sb.append("File name: ").append(e.name).append('\n');
            sb.append("Path: ").append(e.path).append('\n');
        }
        sb.append("Size: ").append(Util.formatSize(e.size)).append('\n');
        sb.append("Sender: ").append(e.sender).append('\n');
        sb.append("Time: ").append(timeString(e.time));
        if (e.message) sb.append("\n\n").append(e.name);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(e.message ? "Message information" : "File information")
                .setMessage(sb.toString())
                .setNegativeButton("Close", null);
        final String copy = e.message ? e.name : e.path;
        b.setPositiveButton(e.message ? "Copy text" : "Copy path", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                Opener.copy(HistoryActivity.this, copy);
            }
        });
        b.show();
    }

    /** In the phone's own date and time format, as LocalSend does. */
    private String timeString(long time) {
        Date d = new Date(time);
        return DateFormat.getDateFormat(this).format(d) + " " + DateFormat.getTimeFormat(this).format(d);
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
            History.Entry e = entries.get(position);
            View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_file, parent, false);
            v.findViewById(R.id.check).setVisibility(View.GONE);
            TextView name = (TextView) v.findViewById(R.id.name);
            // A message shows its text on one line; newlines would otherwise be drawn as boxes on old phones.
            // File names keep their extension visible; a message reads better cut at the end.
            name.setEllipsize(e.message ? TextUtils.TruncateAt.END : TextUtils.TruncateAt.MIDDLE);
            name.setText(e.message ? "“" + e.name.replace('\n', ' ') + "”" : e.name);
            ((TextView) v.findViewById(R.id.detail)).setText(
                    timeString(e.time) + " · " + Util.formatSize(e.size) + " · " + e.sender);
            return v;
        }
    }
}
