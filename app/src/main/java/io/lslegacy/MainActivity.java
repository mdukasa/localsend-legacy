package io.lslegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.ClipboardManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements LsService.Listener {
    private static final int REQ_PICK = 1;
    private static final int REQ_BROWSE = 2;

    private final List<Device> devices = new ArrayList<Device>();
    /** Files handed to us by a share intent, waiting for the user to tap a device. */
    private final ArrayList<Uri> shared = new ArrayList<Uri>();
    private Device pickTarget;
    private TextView status;
    private TextView sharedView;
    private TextView empty;
    private TextView logView;
    private DeviceAdapter adapter;
    private AlertDialog incomingDialog;
    private Receiver.IncomingRequest shownRequest;
    private ProgressDialog progress;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = (TextView) findViewById(R.id.status);
        sharedView = (TextView) findViewById(R.id.shared);
        empty = (TextView) findViewById(R.id.empty);
        logView = (TextView) findViewById(R.id.log);
        ListView list = (ListView) findViewById(R.id.devices);
        adapter = new DeviceAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onDeviceTapped(devices.get(position));
            }
        });
        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LsService s = LsService.get();
                if (s != null) s.refresh();
            }
        });
        findViewById(R.id.scan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final LsService s = LsService.get();
                if (s == null || !s.ready()) return;
                v.setEnabled(false);
                final View btn = v;
                s.scan(new Runnable() {
                    @Override public void run() {
                        btn.setEnabled(true);
                    }
                });
            }
        });
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSettings();
            }
        });

        startService(new Intent(this, LsService.class));
        handleShareIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        // The service may still be starting; poll briefly until it exists.
        attach();
    }

    private void attach() {
        LsService s = LsService.get();
        if (s == null) {
            status.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing()) attach();
                }
            }, 200);
            return;
        }
        s.setListener(this);
        onStateChanged();
        StringBuilder sb = new StringBuilder();
        for (String line : s.log()) sb.append(line).append('\n');
        logView.setText(sb.toString().trim());
        Receiver.IncomingRequest req = s.pendingRequest();
        if (req != null) onIncoming(req);
    }

    @Override protected void onPause() {
        LsService s = LsService.get();
        if (s != null) s.setListener(null);
        super.onPause();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Settings");
        menu.add(0, 2, 0, "Quit (stop receiving)");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showSettings();
            return true;
        }
        if (item.getItemId() == 2) {
            stopService(new Intent(this, LsService.class));
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- LsService.Listener ----

    @Override public void onStateChanged() {
        LsService s = LsService.get();
        if (s == null) return;
        String ip = Util.localIp(this);
        status.setText(s.alias() + " · " + (ip != null ? ip : "no network") + " · " + s.status());
        devices.clear();
        devices.addAll(s.devices());
        adapter.notifyDataSetChanged();
        empty.setText(ip == null ? "Connect to Wi-Fi or Ethernet to find devices." : "Looking for devices…\nOpen LocalSend on the other device, or press Scan.");
        updateShared();
    }

    @Override public void onIncoming(final Receiver.IncomingRequest req) {
        if (req == shownRequest && incomingDialog != null && incomingDialog.isShowing()) return;
        shownRequest = req;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Receiver.FileEntry f : req.files) {
            if (shown++ == 8) {
                sb.append("…and ").append(req.files.size() - 8).append(" more\n");
                break;
            }
            sb.append("• ").append(f.fileName).append(" (").append(Util.formatSize(f.size)).append(")\n");
        }
        sb.append("\nTotal ").append(Util.formatSize(req.totalBytes));
        incomingDialog = new AlertDialog.Builder(this)
                .setTitle(req.sender.alias + " wants to send")
                .setMessage(sb.toString())
                .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        req.decide(true);
                    }
                })
                .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        req.decide(false);
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override public void onMessage(Device from, final String text) {
        new AlertDialog.Builder(this)
                .setTitle("Message from " + from.alias)
                .setMessage(text)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setText(text);
                        Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override public void onLog(String line) {
        CharSequence old = logView.getText();
        logView.setText(old.length() == 0 ? line : line + "\n" + old);
    }

    // ---- sending ----

    private void onDeviceTapped(final Device d) {
        LsService s = LsService.get();
        if (pendingText != null && s != null) {
            startProgress(d);
            s.newSender().sendText(d, pendingText, callback());
            pendingText = null;
            updateShared();
            return;
        }
        if (!shared.isEmpty()) {
            send(d, new ArrayList<Uri>(shared));
            return;
        }
        // Many Android 2.x phones have no app that can pick arbitrary files; only offer one if it exists.
        final boolean hasPicker = !getPackageManager().queryIntentActivities(pickIntent(), 0).isEmpty();
        CharSequence[] items = hasPicker
                ? new CharSequence[]{"Send files from storage", "Send files using another app", "Send text"}
                : new CharSequence[]{"Send files from storage", "Send text"};
        new AlertDialog.Builder(this)
                .setTitle(d.alias)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        pickTarget = d;
                        if (which == 0) {
                            startActivityForResult(new Intent(MainActivity.this, FileBrowserActivity.class), REQ_BROWSE);
                        } else if (hasPicker && which == 1) {
                            startActivityForResult(Intent.createChooser(pickIntent(), "Choose file(s)"), REQ_PICK);
                        } else {
                            askText(d);
                        }
                    }
                })
                .show();
    }

    private static Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        return i;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || pickTarget == null) return;
        if (requestCode == REQ_BROWSE) {
            ArrayList<Uri> uris = data.getParcelableArrayListExtra(FileBrowserActivity.EXTRA_URIS);
            if (uris != null && !uris.isEmpty()) send(pickTarget, uris);
            return;
        }
        if (requestCode != REQ_PICK) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (Build.VERSION.SDK_INT >= 16) collectClip(data, uris);
        if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
        if (!uris.isEmpty()) send(pickTarget, uris);
    }

    @android.annotation.TargetApi(16)
    private static void collectClip(Intent data, List<Uri> out) {
        ClipData clip = data.getClipData();
        if (clip == null) return;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri u = clip.getItemAt(i).getUri();
            if (u != null) out.add(u);
        }
    }

    private void askText(final Device d) {
        final EditText input = new EditText(this);
        input.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Send text to " + d.alias)
                .setView(input)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String t = input.getText().toString();
                        if (t.length() == 0) return;
                        LsService s = LsService.get();
                        if (s == null) return;
                        startProgress(d);
                        s.newSender().sendText(d, t, callback());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void send(Device d, ArrayList<Uri> uris) {
        LsService s = LsService.get();
        if (s == null || !s.ready()) return;
        startProgress(d);
        s.acquireWake();
        s.newSender().sendFiles(d, uris, callback());
    }

    private void startProgress(Device d) {
        progress = new ProgressDialog(this);
        progress.setTitle("Sending to " + d.alias);
        progress.setMessage("Waiting for " + d.alias + " to accept…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                LsService s = LsService.get();
                if (s != null) s.cancelSend();
            }
        });
        progress.show();
    }

    private Sender.Callback callback() {
        final ProgressDialog pd = progress;
        return new Sender.Callback() {
            @Override public void onStatus(final String st) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.setMessage(st);
                    }
                });
            }

            @Override public void onProgress(final long sent, final long total) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.setProgress(total > 0 ? (int) (sent * 100 / total) : 0);
                    }
                });
            }

            @Override public void onDone(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        LsService s = LsService.get();
                        if (s != null) {
                            s.releaseWake();
                            s.addLog(message);
                        }
                        if (pd.isShowing()) pd.dismiss();
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        if (ok) {
                            shared.clear();
                            updateShared();
                        }
                    }
                });
            }
        };
    }

    private void handleShareIntent(Intent i) {
        if (i == null) return;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Parcelable p = i.getParcelableExtra(Intent.EXTRA_STREAM);
            shared.clear();
            if (p instanceof Uri) {
                shared.add((Uri) p);
            } else if (i.getStringExtra(Intent.EXTRA_TEXT) != null) {
                sharedText = i.getStringExtra(Intent.EXTRA_TEXT);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Parcelable> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            shared.clear();
            if (list != null) for (Parcelable p : list) if (p instanceof Uri) shared.add((Uri) p);
        }
        updateShared();
        if (sharedText != null) {
            final String text = sharedText;
            sharedText = null;
            new AlertDialog.Builder(this).setTitle("Send this text?").setMessage(text)
                    .setPositiveButton("Choose device", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            pendingText = text;
                            updateShared();
                        }
                    }).setNegativeButton("Cancel", null).show();
        }
    }

    private String sharedText;
    /** Shared text waiting for a device tap. */
    private String pendingText;

    private void updateShared() {
        if (pendingText != null) {
            sharedView.setVisibility(View.VISIBLE);
            sharedView.setText("Tap a device to send the shared text  (tap here to clear)");
        } else if (!shared.isEmpty()) {
            sharedView.setVisibility(View.VISIBLE);
            sharedView.setText("Tap a device to send " + shared.size() + (shared.size() == 1 ? " file" : " files")
                    + "  (tap here to clear)");
        } else {
            sharedView.setVisibility(View.GONE);
        }
        sharedView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                shared.clear();
                pendingText = null;
                updateShared();
            }
        });
    }

    // ---- settings ----

    private void showSettings() {
        final LsService s = LsService.get();
        if (s == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView label = new TextView(this);
        label.setText("Device name");
        final EditText alias = new EditText(this);
        alias.setSingleLine(true);
        alias.setText(s.alias());
        final CheckBox auto = new CheckBox(this);
        auto.setText("Accept incoming files automatically");
        auto.setChecked(s.autoAccept());
        TextView info = new TextView(this);
        info.setText("Files are saved to " + Receiver.saveDir().getAbsolutePath()
                + "\n\nFingerprint: " + (s.identity() != null ? s.identity().fingerprint.substring(0, 16) + "…" : "-"));
        box.addView(label);
        box.addView(alias);
        box.addView(auto);
        box.addView(info);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        s.saveSettings(alias.getText().toString(), auto.isChecked());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() {
            return devices.size();
        }

        @Override public Object getItem(int position) {
            return devices.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            Device d = devices.get(position);
            ((TextView) v.findViewById(android.R.id.text1)).setText(d.alias);
            ((TextView) v.findViewById(android.R.id.text2)).setText(d.subtitle());
            return v;
        }
    }
}
