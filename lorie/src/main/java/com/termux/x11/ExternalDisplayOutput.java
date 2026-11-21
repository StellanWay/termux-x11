package com.termux.x11;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * [external-display] Shows the X11 picture on a secondary display (XR glasses, HDMI, ...) by handing
 * that display's SurfaceView surface to the single native renderer owned by {@link LorieView}.
 *
 * Nothing is rendered twice: the renderer keeps drawing one quad per frame, just to a different
 * ANativeWindow. The built-in screen keeps all input focus and acts as a trackpad.
 */
public final class ExternalDisplayOutput implements DisplayManager.DisplayListener {
    private static final String TAG = "ExternalDisplayOutput";

    public interface Callback {
        /** The display's surfaces are ready; the renderer should draw there from now on. */
        void onSurfaceReady(Surface surface, Surface cursorSurface, int width, int height, float refreshRate);

        /** The pointer's surface was recreated, or is gone when null. */
        void onCursorSurfaceReady(Surface cursorSurface);

        /** The display went away; the renderer must fall back to the built-in surface. */
        void onSurfaceGone();
    }

    private final MainActivity activity;
    private final Callback callback;
    private final DisplayManager displayManager;
    private Presentation presentation;
    private SurfaceView cursorView;
    private Surface cursorSurface;
    private int displayId = Display.INVALID_DISPLAY;

    public ExternalDisplayOutput(MainActivity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Starts watching for secondary displays and presents on one right away, if there is any. */
    public void start() {
        displayManager.registerDisplayListener(this, null);
        sync();
    }

    public void stop() {
        displayManager.unregisterDisplayListener(this);
        dismiss();
    }

    @Override public void onDisplayAdded(int id) { sync(); }
    @Override public void onDisplayChanged(int id) { if (id == displayId) sync(); }
    @Override public void onDisplayRemoved(int id) { if (id == displayId) dismiss(); }

    private void sync() {
        Display target = null;
        // Only displays which are not a mirror of the built-in screen can host a presentation.
        for (Display d : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
            if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) {
                target = d;
                break;
            }

        if (target == null) {
            dismiss();
            return;
        }

        if (presentation != null && displayId == target.getDisplayId())
            return;

        dismiss();
        show(target);
    }

    private void show(Display display) {
        final Presentation p = new Presentation(activity, display);
        final SurfaceView view = new SurfaceView(activity);
        final SurfaceView pointer = new SurfaceView(activity);
        // The pointer is a layer of its own, drawn by the X server and placed here.
        pointer.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        pointer.setZOrderMediaOverlay(true);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(pointer, new FrameLayout.LayoutParams(32, 32));

        Window w = p.getWindow();
        if (w != null) {
            // The built-in screen keeps input focus: keyboard, IME and pointer stay there.
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        // Force the surface to the display's native mode, so the picture is a 1:1 unscaled blit
        // no matter what insets the presentation window ends up with.
        Display.Mode mode = display.getMode();
        if (mode != null)
            view.getHolder().setFixedSize(mode.getPhysicalWidth(), mode.getPhysicalHeight());

        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {}

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (presentation != p)
                    return;

                Rect frame = holder.getSurfaceFrame();
                Log.d(TAG, "external output surface is " + frame.width() + "x" + frame.height() +
                        " @" + display.getRefreshRate() + "Hz on display " + display.getDisplayId());
                callback.onSurfaceReady(holder.getSurface(), cursorSurface, frame.width(), frame.height(), display.getRefreshRate());
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (presentation == p)
                    callback.onSurfaceGone();
            }
        });

        pointer.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {}

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (presentation != p)
                    return;

                cursorSurface = holder.getSurface();
                callback.onCursorSurfaceReady(cursorSurface);
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (presentation != p)
                    return;

                cursorSurface = null;
                callback.onCursorSurfaceReady(null);
            }
        });

        p.setOnDismissListener(d -> {
            if (presentation == p) {
                presentation = null;
                cursorView = null;
                cursorSurface = null;
                displayId = Display.INVALID_DISPLAY;
            }
            callback.onSurfaceGone();
        });
        p.setContentView(root);

        try {
            p.show();
            presentation = p;
            cursorView = pointer;
            displayId = display.getDisplayId();
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "the display vanished before the presentation could be shown", e);
            callback.onSurfaceGone();
        }
    }

    /** Puts the pointer's layer where it belongs on the presented picture. */
    public void placeCursor(int x, int y, int width, int height) {
        SurfaceView view = cursorView;
        if (view == null)
            return;

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
        view.setTranslationX(x);
        view.setTranslationY(y);
    }

    private void dismiss() {
        Presentation p = presentation;
        presentation = null;
        cursorView = null;
        cursorSurface = null;
        displayId = Display.INVALID_DISPLAY;
        // The dismiss listener reports the surface going away, so this is the only place that does.
        if (p != null)
            p.dismiss();
    }
}
