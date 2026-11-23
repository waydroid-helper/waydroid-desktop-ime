package org.waydroid.helper.desktopime;

import android.inputmethodservice.InputMethodService;
import android.graphics.RectF;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** The Android editor owns its text, selection and composing span. The host supplies edits. */
public final class HostIme extends InputMethodService {
    static HostIme instance;
    private String editor;
    private boolean composing;
    private JSONObject anchor;

    @Override public void onCreate() { super.onCreate(); instance = this; }

    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public View onCreateInputView() {
        // The real keyboard and its candidate UI live on the desktop. An empty
        // wrap-content view participates in the normal Android show/hide lifecycle.
        return new FrameLayout(this);
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        endEditor();
    }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (info.inputType == InputType.TYPE_NULL || getCurrentInputConnection() == null) return;
        editor = UUID.randomUUID().toString();
        connected();
    }

    void connected() {
        if (editor == null) { BridgeProvider.send(message("close")); return; }
        EditorInfo info = getCurrentInputEditorInfo();
        JSONObject msg = message("open");
        try {
            msg.put("type", info.inputType);
            msg.put("private", (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
        BridgeProvider.send(msg);
        if (anchor != null) BridgeProvider.send(anchor);
        InputConnection input = getCurrentInputConnection();
        if (input != null) input.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR);
    }

    @Override public void onUpdateCursorAnchorInfo(CursorAnchorInfo info) {
        super.onUpdateCursorAnchorInfo(info);
        if (editor == null || info == null) return;
        float[] points = {info.getInsertionMarkerHorizontal(), info.getInsertionMarkerTop(),
                info.getInsertionMarkerHorizontal(), info.getInsertionMarkerBottom()};
        if (!Float.isFinite(points[0]) || !Float.isFinite(points[1]) || !Float.isFinite(points[3])) {
            // Some WebViews supply character or editor bounds instead of an
            // insertion marker. Use guest coordinates when available; the host
            // otherwise keeps the most recent canvas click as the candidate spot.
            RectF bounds = info.getCharacterBounds(Math.max(0, info.getSelectionStart() - 1));
            if (bounds == null && Build.VERSION.SDK_INT >= 33 && info.getEditorBoundsInfo() != null)
                bounds = info.getEditorBoundsInfo().getEditorBounds();
            if (bounds == null) return;
            points = new float[]{bounds.left, bounds.top, bounds.left, bounds.bottom};
        }
        info.getMatrix().mapPoints(points);
        for (float n : points) if (!Float.isFinite(n)) return;
        try {
            JSONObject next = message("caret").put("rect", new JSONArray()
                    .put(Math.round(points[0])).put(Math.round(points[1])).put(Math.round(points[3])));
            if (anchor == null || !next.toString().equals(anchor.toString())) {
                anchor = next;
                BridgeProvider.send(next);
            }
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    void apply(JSONObject command) {
        // Text queued before switching fields must never land in the new field.
        boolean current = editor != null && editor.equals(command.optString("id"));
        if (android.util.Log.isLoggable("WaydroidHostIme", android.util.Log.DEBUG))
            android.util.Log.d("WaydroidHostIme", "Editor operation " + command.optString("op")
                    + " current=" + current);
        if (!current) return;
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        String text = command.optString("text");
        switch (command.optString("op")) {
            case "preedit":
                if (text.isEmpty()) cancelComposition();
                else composing = input.setComposingText(text, 1);
                break;
            case "commit":
                input.commitText(text, 1);
                composing = false;
                break;
            case "enter":
                if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER);
                break;
            case "hide":
                requestHideSelf(0);
                break;
        }
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int start, int end,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, start, end, candidatesStart, candidatesEnd);
        if (editor != null && composing && (start != oldStart || end != oldEnd)
                && candidatesEnd >= 0 && (start != candidatesEnd || end != candidatesEnd)) {
            InputConnection input = getCurrentInputConnection();
            if (input != null) input.finishComposingText();
            composing = false;
            BridgeProvider.send(message("reset"));
        }
    }

    void cancelComposition() {
        InputConnection input = getCurrentInputConnection();
        if (composing && input != null) {
            input.setComposingText("", 1);
            input.finishComposingText();
        }
        composing = false;
    }

    private JSONObject message(String event) {
        try { return new JSONObject().put("event", event).put("id", editor); }
        catch (JSONException impossible) { throw new AssertionError(impossible); }
    }
    private void endEditor() {
        if (editor != null) BridgeProvider.send(message("close"));
        editor = null;
        anchor = null;
        composing = false;
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        cancelComposition(); endEditor(); super.onFinishInputView(finishingInput);
    }
    @Override public void onFinishInput() { endEditor(); super.onFinishInput(); }
    @Override public void onWindowHidden() { endEditor(); super.onWindowHidden(); }
    @Override public void onDestroy() { endEditor(); instance = null; super.onDestroy(); }
}
