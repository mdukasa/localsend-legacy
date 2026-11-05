package io.lslegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.ClipboardManager;
import android.text.util.Linkify;
import android.view.Gravity;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private OrbView orb;
    private TextView aliasView;
    private TextView status;
    private TextView sharedView;
    private TextView empty;
    private TextView logView;
    private DeviceAdapter adapter;
    private AlertDialog incomingDialog;
    private Receiver.IncomingRequest shownRequest;
    private Card receiveCard;
    private Card sendCard;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        orb = (OrbView) findViewById(R.id.orb);
        aliasView = (TextView) findViewById(R.id.alias);
        status = (TextView) findViewById(R.id.status);
        sharedView = (TextView) findViewById(R.id.shared);
        empty = (TextView) findViewById(R.id.empty);
        logView = (TextView) findViewById(R.id.log);
        receiveCard = new Card(findViewById(R.id.card_receive));
        sendCard = new Card(findViewById(R.id.card_send));
        ListView list = (ListView) findViewById(R.id.devices);
        adapter = new DeviceAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onDeviceTapped(devices.get(position));
            }
        });
        // Refresh re-announces over multicast and also scans the subnet, for networks that drop multicast.
        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                LsService s = LsService.get();
                if (s == null || !s.ready()) return;
                s.refresh();
                v.setEnabled(false);
                s.scan(new Runnable() {
                    @Override public void run() {
                        v.setEnabled(true);
                    }
                });
            }
        });
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSettings();
            }
        });

        alignHeaderText();
        startService(new Intent(this, LsService.class));
        handleShareIntent(getIntent());
    }

    /**
     * Button backgrounds are drawn inset from the view edge (a shadow margin that differs between
     * Gingerbread, Holo and custom ROM themes), so indent the name and status by the same amount to
     * line them up with the visible left edge of the Refresh button.
     */
    private void alignHeaderText() {
        final View button = findViewById(R.id.refresh);
        button.post(new Runnable() {
            @Override public void run() {
                int w = button.getWidth(), h = button.getHeight();
                Drawable bg = button.getBackground();
                if (w == 0 || h == 0 || bg == null) return;
                Drawable d = bg.getConstantState() != null ? bg.getConstantState().newDrawable().mutate() : bg;
                d.setState(new int[]{android.R.attr.state_enabled});
                d.setBounds(0, 0, w, h);
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                d.draw(new Canvas(bitmap));
                int inset = 0;
                for (int x = 0; x < w / 2; x++) {
                    if (Color.alpha(bitmap.getPixel(x, h / 2)) > 64) {
                        inset = x;
                        break;
                    }
                }
                bitmap.recycle();
                aliasView.setPadding(inset, 0, 0, 0);
                status.setPadding(inset, 0, 0, 0);
            }
        });
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
        onTransfer();
        StringBuilder sb = new StringBuilder();
        for (String line : s.log()) sb.append(line).append('\n');
        logView.setText(sb.toString().trim());
        Receiver.IncomingRequest req = s.pendingRequest();
        if (req != null) onIncoming(req);
    }

    @Override protected void onPause() {
        LsService s = LsService.get();
        if (s != null) s.setListener(null);
        status.removeCallbacks(tick);
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
        aliasView.setText(s.alias());
        status.setText((ip != null ? ip : "No network") + " · " + s.status());
        // Spins like LocalSend's receive screen while the phone can be found and receive.
        orb.setActive(ip != null && s.ready());
        devices.clear();
        devices.addAll(s.devices());
        adapter.notifyDataSetChanged();
        empty.setText(ip == null ? "Connect to Wi-Fi or Ethernet to find devices." : "Looking for devices…\nOpen LocalSend on the other device, or press Refresh.");
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

    @Override public void onTransfer() {
        LsService s = LsService.get();
        receiveCard.show(s != null ? s.receiving() : null);
        sendCard.show(s != null ? s.sending() : null);
        // Progress only arrives while bytes move; tick once a second too so the speed falls during a stall.
        status.removeCallbacks(tick);
        if (receiveCard.shown != null || sendCard.shown != null) status.postDelayed(tick, 1000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            onTransfer();
        }
    };

    @Override public void onSendDone(boolean ok, String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (ok) {
            shared.clear();
            updateShared();
        }
    }

    /** The views of one transfer card (layout/transfer_card.xml). */
    private static final class Card {
        final View root;
        final TextView title, percent, file, detail;
        final ProgressBar bar;
        final View cancel;
        Transfer shown;

        Card(View root) {
            this.root = root;
            title = (TextView) root.findViewById(R.id.transfer_title);
            percent = (TextView) root.findViewById(R.id.transfer_percent);
            file = (TextView) root.findViewById(R.id.transfer_file);
            detail = (TextView) root.findViewById(R.id.transfer_detail);
            bar = (ProgressBar) root.findViewById(R.id.transfer_bar);
            cancel = root.findViewById(R.id.transfer_cancel);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    LsService s = LsService.get();
                    if (s != null) s.cancel(shown);
                    v.setEnabled(false);
                }
            });
        }

        void show(Transfer t) {
            if (t == null) {
                shown = null;
                root.setVisibility(View.GONE);
                return;
            }
            if (t != shown) {
                shown = t;
                title.setText((t.sending ? "Sending to " : "Receiving from ") + t.peer);
                cancel.setEnabled(true);
                root.setVisibility(View.VISIBLE);
            }
            if (!t.started()) {
                percent.setText("");
                file.setText(t.sending ? "Waiting for " + t.peer + " to accept…" : "Starting…");
                bar.setProgress(0);
                detail.setText(t.total > 0 ? Util.formatSize(t.total) : "");
                return;
            }
            percent.setText(t.percent() + "%");
            file.setText(t.fileCount > 1 ? t.file + "  (" + t.fileIndex + " of " + t.fileCount + ")" : t.file);
            bar.setProgress((int) (t.total > 0 ? t.done * 1000 / t.total : 0));
            StringBuilder sb = new StringBuilder();
            sb.append(Util.formatSize(t.done)).append(" of ").append(Util.formatSize(t.total));
            long speed = t.speed();
            if (speed > 0) {
                sb.append(" · ").append(Util.formatSize(speed)).append("/s · ")
                        .append(Util.formatDuration(t.secondsLeft())).append(" left");
            }
            detail.setText(sb.toString());
        }
    }

    // ---- sending ----

    private void onDeviceTapped(final Device d) {
        LsService s = LsService.get();
        if (pendingText != null && s != null) {
            if (!s.sendText(d, pendingText)) {
                busy();
                return;
            }
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
                        if (s != null && !s.sendText(d, t)) busy();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void send(Device d, ArrayList<Uri> uris) {
        LsService s = LsService.get();
        if (s == null || !s.ready()) return;
        if (!s.sendFiles(d, uris)) busy();
    }

    private void busy() {
        Toast.makeText(this, "Wait for the current send to finish, or cancel it", Toast.LENGTH_LONG).show();
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
        box.addView(aboutView());
        // Scrolls on short screens (a Wildfire S is 480 px tall).
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        s.saveSettings(alias.getText().toString(), auto.isChecked());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The "About" block at the bottom of Settings, after LocalSend's own: orb, name, version, credits. */
    private View aboutView() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL);
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        about.setPadding(0, (int) (16 * dp), 0, 0);

        View divider = new View(this);
        divider.setBackgroundColor(0x40888888);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dividerParams.bottomMargin = (int) (16 * dp);
        about.addView(divider, dividerParams);

        // Still, like LocalSend's About logo; two spinning orbs at once would double the redraw cost.
        OrbView logo = new OrbView(this);
        logo.setAnimated(false);
        about.addView(logo, new LinearLayout.LayoutParams((int) (72 * dp), (int) (72 * dp)));

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        about.addView(name);

        String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        TextView details = new TextView(this);
        // The mask must be set before the text: Gingerbread only linkifies text set after it.
        details.setAutoLinkMask(Linkify.WEB_URLS);
        details.setText("Version: " + version
                + "\n© 2026 mdukasa · Apache License 2.0"
                + "\nUnofficial. Works with LocalSend, but not affiliated with the LocalSend project."
                + "\nhttps://github.com/mdukasa/localsend-legacy");
        details.setGravity(Gravity.CENTER);
        details.setTextSize(13);
        about.addView(details);
        return about;
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
