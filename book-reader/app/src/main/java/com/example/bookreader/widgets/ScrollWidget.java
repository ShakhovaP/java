package com.example.bookreader.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.androidlibrary.widgets.TopAlwaysSmoothScroller;
import com.example.bookreader.app.PDFPlugin;
import com.example.bookreader.app.Plugin;
import com.example.bookreader.app.Reflow;

import org.geometerplus.fbreader.fbreader.FBView;
import org.geometerplus.fbreader.fbreader.options.PageTurningOptions;
import org.geometerplus.zlibrary.core.view.ZLView;
import org.geometerplus.zlibrary.core.view.ZLViewEnums;
import org.geometerplus.zlibrary.core.view.ZLViewWidget;
import org.geometerplus.zlibrary.text.view.ZLTextElementAreaVector;
import org.geometerplus.zlibrary.text.view.ZLTextFixedPosition;
import org.geometerplus.zlibrary.text.view.ZLTextPosition;
import org.geometerplus.zlibrary.text.view.ZLTextRegion;
import org.geometerplus.zlibrary.ui.android.view.ZLAndroidPaintContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ScrollWidget extends RecyclerView implements ZLViewWidget {
    FBReaderView fb;
    public LinearLayoutManager lm;
    public ScrollAdapter adapter = new ScrollAdapter();
    Gestures gesturesListener;

    public class ScrollAdapter extends RecyclerView.Adapter<ScrollAdapter.PageHolder> {
        public ArrayList<PageCursor> pages = new ArrayList<>(); // adapter items
        final Object lock = new Object();
        Thread thread;
        Plugin.Box size = new Plugin.Box(); // ScrollView size, after reset
        Set<PageHolder> invalidates = new HashSet<>(); // pending invalidates
        ArrayList<PageHolder> holders = new ArrayList<>(); // keep all active holders, including Recycler.mCachedViews
        ZLTextPosition oldTurn; // last page shown

        public class PageView extends View {
            public PageHolder holder;
            TimeAnimatorCompat time;
            FrameLayout progress;
            ProgressBar progressBar;
            TextView progressText;
            Bitmap bm; // cache bitmap
            PageCursor cache; // cache cursor

            ZLTextElementAreaVector text;
            Reflow.Info info;
            SelectionView.PageView selection;
            FBReaderView.LinksView links;
            FBReaderView.SearchView search;

            public PageView(ViewGroup parent) {
                super(parent.getContext());
                progress = new FrameLayout(getContext());

                progressBar = new ProgressBar(getContext()) {
                    Handler handler = new Handler();

                    @Override
                    public void draw(Canvas canvas) {
                        super.draw(canvas);
                        onAttachedToWindow(); // startAnimation
                    }

                    @Override
                    public int getVisibility() {
                        return VISIBLE;
                    }

                    @Override
                    public int getWindowVisibility() {
                        return VISIBLE;
                    }

                    @Override
                    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                        if (time != null)
                            handler.postAtTime(what, when);
                        else
                            onDetachedFromWindow(); // stopAnimation
                    }
                };
                progressBar.setIndeterminate(true);
                progress.addView(progressBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                progressText = new TextView(getContext());
                progressText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                progress.addView(progressText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int w = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
                int h = getMainAreaHeight();
                setMeasuredDimension(w, h);
            }

            PageCursor current() {
                int page = holder.getAdapterPosition();
                if (page == -1)
                    return null;
                return pages.get(page);
            }

            @Override
            protected void onDraw(Canvas draw) {
                final PageCursor c = current();
                if (c == null) {
                    invalidate();
                    return;
                }
                if (isCached(c)) {
                    drawCache(draw);
                    return;
                }
                if (fb.pluginview != null) {
                    open(c);
                    fb.pluginview.drawOnCanvas(getContext(), draw, getWidth(), getHeight(), ZLViewEnums.PageIndex.current, (FBReaderView.CustomView) fb.app.BookTextView, fb.book.info);
                    update();
                } else {
                    open(c);
                    final ZLAndroidPaintContext context = new ZLAndroidPaintContext(
                            fb.app.SystemInfo,
                            draw,
                            new ZLAndroidPaintContext.Geometry(
                                    getWidth(),
                                    getHeight(),
                                    getWidth(),
                                    getHeight(),
                                    0,
                                    0
                            ),
                            getVerticalScrollbarWidth()
                    );
                    fb.app.BookTextView.paint(context, ZLViewEnums.PageIndex.current);
                    text = fb.app.BookTextView.myCurrentPage.TextElementMap;
                    fb.app.BookTextView.myCurrentPage.TextElementMap = new ZLTextElementAreaVector();
                    update();
                }
            }

            void recycle() {
                if (bm != null) {
                    bm.recycle();
                    bm = null;
                }
                info = null;
                text = null;
                if (links != null) {
                    links.close();
                    links = null;
                }
                if (search != null) {
                    search.close();
                    search = null;
                }
                selection = null;
                if (time != null) {
                    time.cancel();
                    time = null;
                }
            }

            boolean isCached(PageCursor c) {
                if (cache == null || cache != c) // should be same 'cache' memory ref
                    return false;
                if (bm == null)
                    return false;
                return true;
            }

            void drawCache(Canvas draw) {
                Rect src = new Rect(0, 0, bm.getWidth(), bm.getHeight());
                Rect dst = new Rect(0, 0, getWidth(), getHeight());
                draw.drawBitmap(bm, src, dst, fb.pluginview.paint);
            }
        }

        public class PageHolder extends RecyclerView.ViewHolder {
            public PageView page;

            public PageHolder(PageView p) {
                super(p);
                page = p;
            }
        }

        public class PageCursor {
            public ZLTextPosition start;
            public ZLTextPosition end;

            public PageCursor(ZLTextPosition s, ZLTextPosition e) {
                if (s != null)
                    start = new ZLTextFixedPosition(s);
                if (e != null)
                    end = new ZLTextFixedPosition(e);
            }

            public boolean equals(ZLTextPosition p1, ZLTextPosition p2) {
                return p1.getCharIndex() == p2.getCharIndex() && p1.getElementIndex() == p2.getElementIndex() && p1.getParagraphIndex() == p2.getParagraphIndex();
            }

            @Override
            public boolean equals(Object obj) {
                PageCursor p = (PageCursor) obj;
                if (start != null && p.start != null) {
                    if (equals(start, p.start))
                        return true;
                }
                if (end != null && p.end != null) {
                    if (equals(end, p.end))
                        return true;
                }
                return false;
            }

            public void update(PageCursor c) {
                if (c.start != null)
                    start = c.start;
                if (c.end != null)
                    end = c.end;
            }

            @Override
            public String toString() {
                String str = "";
                String format = "[%d,%d,%d]";
                if (start == null)
                    str += "- ";
                else
                    str += String.format(format, start.getParagraphIndex(), start.getElementIndex(), start.getCharIndex());
                if (end == null)
                    str += " -";
                else {
                    if (start != null)
                        str += " - ";
                    str += String.format(format, end.getParagraphIndex(), end.getElementIndex(), end.getCharIndex());
                }
                return str;
            }
        }

        public ScrollAdapter() {
        }

        void open(PageCursor c) {
            if (c.start == null) {
                if (fb.pluginview != null) {
                    fb.pluginview.gotoPosition(c.end);
                    fb.pluginview.onScrollingFinished(ZLViewEnums.PageIndex.previous);
                    fb.pluginview.current.pageOffset = 0; // widget instanceof ScrollView
                    c.update(getCurrent());
                } else {
                    fb.app.BookTextView.gotoPosition(c.end);
                    fb.app.BookTextView.onScrollingFinished(ZLViewEnums.PageIndex.previous);
                    c.update(getCurrent());
                }
            } else {
                if (fb.pluginview != null)
                    fb.pluginview.gotoPosition(c.start);
                else {
                    PageCursor cc = getCurrent();
                    if (!cc.equals(c)) {
                        fb.app.BookTextView.gotoPosition(c.start, c.end);
                    }
                }
            }
        }

        public int findPage(PageCursor c) {
            if (c.start != null && c.end != null) {
                for (int i = 0; i < pages.size(); i++) {
                    PageCursor k = pages.get(i);
                    if (c.equals(k))
                        return i;
                }
            } else if (c.start == null && c.end != null) {
                return findPage(c.end);
            } else if (c.start != null) {
                return findPage(c.start);
            }
            return -1;
        }

        public int findPage(ZLTextPosition p) {
            for (int i = 0; i < pages.size(); i++) {
                PageCursor c = pages.get(i);
                if (c.start != null && c.end != null) {
                    if (c.start.compareTo(p) <= 0 && c.end.compareTo(p) > 0)
                        return i;
                } else if (c.start == null && c.end != null) {
                    if (c.end.compareTo(p) > 0)
                        return i;
                } else if (c.start != null) {
                    if (c.start.compareTo(p) <= 0)
                        return i;
                }
            }
            return -1;
        }

        @Override
        public PageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new PageHolder(new PageView(parent));
        }

        @Override
        public void onBindViewHolder(PageHolder holder, int position) {
            holder.page.holder = holder;
            holders.add(holder);
        }

        @Override
        public void onViewRecycled(PageHolder holder) {
            super.onViewRecycled(holder);
            holder.page.recycle();
            holder.page.holder = null;
            holders.remove(holder);
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        public void reset() { // read current position
            size.w = getWidth();
            size.h = getHeight();
            getRecycledViewPool().clear();
            pages.clear();
            if (fb.app.Model != null) {
                fb.app.BookTextView.preparePage(((FBReaderView.CustomView) fb.app.BookTextView).createContext(new Canvas()), ZLViewEnums.PageIndex.current);
                PageCursor c = getCurrent();
                pages.add(c);
                oldTurn = c.start;
            }
            postInvalidate();
            notifyDataSetChanged();
        }

        PageCursor getCurrent() {
            if (fb.pluginview != null) {
                    return new PageCursor(fb.pluginview.getPosition(), fb.pluginview.getNextPosition());
            } else {
                return new PageCursor(fb.app.BookTextView.getStartCursor(), fb.app.BookTextView.getEndCursor());
            }
        }

        void update() {
            if (fb.app.Model == null)
                return;
            PageCursor c = getCurrent();
            int page;
            for (page = 0; page < pages.size(); page++) {
                PageCursor p = pages.get(page);
                if (p.equals(c)) {
                    p.update(c);
                    break;
                }
            }
            if (page == pages.size()) { // not found == 0
                pages.add(c);
                notifyItemInserted(page);
            }
            if (fb.app.BookTextView.canScroll(ZLViewEnums.PageIndex.previous)) {
                if (page == 0) {
                    pages.add(page, new PageCursor(null, c.start));
                    notifyItemInserted(page);
                    page++; // 'c' page moved to + 1
                }
            }
            if (fb.app.BookTextView.canScroll(ZLViewEnums.PageIndex.next)) {
                if (page == pages.size() - 1) {
                    page++;
                    pages.add(page, new PageCursor(c.end, null));
                    notifyItemInserted(page);
                }
            }
        }

        void processInvalidate() {
            for (ScrollAdapter.PageHolder h : invalidates) {
                h.page.recycle();
                h.page.invalidate();
            }
        }

        void processClear() {
            invalidates.clear();
        }
    }

    public class Gestures implements GestureDetector.OnGestureListener {
        MotionEvent e;
        int x;
        int y;
        ScrollAdapter.PageView v;
        ScrollAdapter.PageCursor c;
        FBReaderView.PinchGesture pinch;
        GestureDetectorCompat gestures;
        FBReaderView.BrightnessGesture brightness;

        Gestures() {
            gestures = new GestureDetectorCompat(fb.getContext(), this);
            brightness = new FBReaderView.BrightnessGesture(fb);

            if (Looper.myLooper() != null) {
                pinch = new FBReaderView.PinchGesture(fb) {
                    @Override
                    public void onScaleBegin(float x, float y) {
                        ScrollAdapter.PageView v = findView(x, y);
                        if (v == null)
                            return;
                        int pos = v.holder.getAdapterPosition();
                        if (pos == -1)
                            return;
                        ScrollAdapter.PageCursor c = adapter.pages.get(pos);
                        int page;
                        if (c.start == null)
                            page = c.end.getParagraphIndex() - 1;
                        else
                            page = c.start.getParagraphIndex();
                        pinchOpen(page, new Rect(v.getLeft(), v.getTop(), v.getLeft() + v.getWidth(), v.getTop() + v.getHeight()));
                    }
                };
            }
        }

        boolean open(MotionEvent e) {
            if (!openCursor(e))
                return false;
            return openText(e);
        }

        boolean openCursor(MotionEvent e) {
            this.e = e;
            v = findView(e);
            if (v == null)
                return false;
            x = (int) (e.getX() - v.getLeft());
            y = (int) (e.getY() - v.getTop());
            int pos = v.holder.getAdapterPosition();
            if (pos == -1)
                return false;
            c = adapter.pages.get(pos);
            return true;
        }

        boolean openText(MotionEvent e) {
            if (v.text == null)
                return false;
            if (!fb.app.BookTextView.getStartCursor().samePositionAs(c.start))
                fb.app.BookTextView.gotoPosition(c.start);
            fb.app.BookTextView.myCurrentPage.TextElementMap = v.text;
            return true;
        }

        void closeText() {
            fb.app.BookTextView.myCurrentPage.TextElementMap = new ZLTextElementAreaVector();
        }

        @Override
        public boolean onDown(MotionEvent e) {
            if (fb.app.BookTextView.mySelection.isEmpty())
                return false;
            if (!open(e))
                return false;
            fb.app.BookTextView.onFingerPress(x, y);
            v.invalidate();
            closeText();
            return true;
        }

        @Override
        public void onShowPress(MotionEvent e) {
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (!open(e)) { // pluginview or reflow
                ((FBReaderView.CustomView) fb.app.BookTextView).onFingerSingleTapLastResort(e);
                return true;
            }
            fb.app.BookTextView.onFingerSingleTap(x, y);
            v.invalidate();
            adapter.invalidates.add(v.holder);
            closeText();
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (fb.app.BookTextView.mySelection.isEmpty())
                return false;
            if (!open(e))
                return false;
            fb.app.BookTextView.onFingerMove(x, y);
            v.invalidate();
            adapter.invalidates.add(v.holder);
            closeText();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (!openCursor(e))
                return;
            if (fb.pluginview != null) {
                Plugin.View.Selection s = fb.pluginview.select(c.start, v.info, v.getWidth(), v.getHeight(), x, y);
                if (s != null) {
                        fb.selectionOpen(s);
                    return;
                }
                    fb.selectionClose();
            }
            if (!openText(e))
                return;
                fb.app.BookTextView.onFingerLongPress(x, y);
                fb.app.BookTextView.onFingerReleaseAfterLongPress(x, y);
            v.invalidate();
            adapter.invalidates.add(v.holder);
            closeText();
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return false;
        }

        public boolean onReleaseCheck(MotionEvent e) {
            if (fb.app.BookTextView.mySelection.isEmpty())
                return false;
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if (!open(e))
                    return false;
                fb.app.BookTextView.onFingerRelease(x, y);
                v.invalidate();
                closeText();
                return true;
            }
            return false;
        }

        public boolean onCancelCheck(MotionEvent e) {
            if (fb.app.BookTextView.mySelection.isEmpty())
                return false;
            if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                fb.app.BookTextView.onFingerEventCancelled();
                v.invalidate();
                return true;
            }
            return false;
        }

        public boolean onFilter(MotionEvent e) {
            if (fb.app.BookTextView.mySelection.isEmpty())
                return false;
            return true;
        }

        public boolean onTouchEvent(MotionEvent e) {
            if (pinch.onTouchEvent(e))
                return true;
            onReleaseCheck(e);
            onCancelCheck(e);
            if (brightness.onTouchEvent(e))
                return true;
            if (gestures.onTouchEvent(e))
                return true;
            if (onFilter(e))
                return true;
            return false;
        }
    }

    public ScrollWidget(final FBReaderView view) {
        super(view.getContext());
        this.fb = view;

        gesturesListener = new Gestures();

        lm = new LinearLayoutManager(fb.getContext()) {
            int idley;
            Runnable idle = new Runnable() {
                @Override
                public void run() {
                    if (idley >= 0) {
                        int page = findLastPage();
                        int next = page + 1;
                        if (next < adapter.pages.size()) {
                            RecyclerView.ViewHolder h = findViewHolderForAdapterPosition(next);
                            if (h != null)
                                h.itemView.draw(new Canvas());
                        }
                    } else {
                        int page = findFirstPage();
                        int prev = page - 1;
                        if (prev >= 0) {
                            RecyclerView.ViewHolder h = findViewHolderForAdapterPosition(prev);
                            if (h != null)
                                h.itemView.draw(new Canvas());
                        }
                    }
                }
            };

            @Override
            public int scrollVerticallyBy(int dy, Recycler recycler, State state) {
                int off = super.scrollVerticallyBy(dy, recycler, state);
                if (fb.pluginview != null)
                    updateOverlays();
                idley = dy;
                fb.removeCallbacks(idle);
                return off;
            }

            @Override
            public void smoothScrollToPosition(RecyclerView recyclerView, State state, int position) {
                PowerManager pm = (PowerManager) fb.getContext().getSystemService(Context.POWER_SERVICE);
                if (Build.VERSION.SDK_INT >= 21 && pm.isPowerSaveMode()) {
                    scrollToPositionWithOffset(position, 0);
                    idley = position - findFirstPage();
                    onScrollStateChanged(SCROLL_STATE_IDLE);
                } else {
                    RecyclerView.SmoothScroller smoothScroller = new TopAlwaysSmoothScroller(recyclerView.getContext());
                    smoothScroller.setTargetPosition(position);
                    startSmoothScroll(smoothScroller);
                }
            }

            @Override
            public void onScrollStateChanged(int state) {
                super.onScrollStateChanged(state);
                fb.removeCallbacks(idle);
                fb.postDelayed(idle, 1000);
            }

            @Override
            public void onLayoutCompleted(State state) {
                super.onLayoutCompleted(state);
                if (fb.pluginview != null)
                    updateOverlays();
            }

            @Override
            protected int getExtraLayoutSpace(State state) {
                return getMainAreaHeight(); // when we need to start preloading to work = full screen
            }

            @Override
            public void onDetachedFromWindow(RecyclerView view, Recycler recycler) {
                super.onDetachedFromWindow(view, recycler);
                fb.removeCallbacks(idle); // drawCache() crash after closeBook()
            }
        };

        setLayoutManager(lm);
        setAdapter(adapter);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(fb.getContext(), DividerItemDecoration.VERTICAL);
        addItemDecoration(dividerItemDecoration);

        setPadding(0, 0, 0, getHeight() - getMainAreaHeight()); // footer height

        setItemAnimator(null);

        fb.config.setValue(fb.app.PageTurningOptions.FingerScrolling, PageTurningOptions.FingerScrollingType.byFlick);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (gesturesListener.onTouchEvent(e))
            return true;
        return super.onTouchEvent(e);
    }

    ScrollAdapter.PageView findView(MotionEvent e) {
        return findView(e.getX(), e.getY());
    }

    ScrollAdapter.PageView findView(float x, float y) {
        for (int i = 0; i < lm.getChildCount(); i++) {
            ScrollAdapter.PageView view = (ScrollAdapter.PageView) lm.getChildAt(i);
            if (view.getLeft() < view.getRight() && view.getTop() < view.getBottom() && x >= view.getLeft() && x < view.getRight() && y >= view.getTop() && y < view.getBottom())
                return view;
        }
        return null;
    }

    public ScrollAdapter.PageView findRegionView(ZLTextRegion.Soul soul) {
        for (ScrollAdapter.PageHolder h : adapter.holders) {
            ScrollAdapter.PageView view = h.page;
            if (view.text != null && view.text.getRegion(soul) != null)
                return view;
        }
        return null;
    }

    @Override
    public void reset() {
        postInvalidate();
    }

    @Override
    public void repaint() {
    }

    public int getViewPercent(View view) {
        int h = 0;
        int b = getMainAreaHeight();
        if (view.getBottom() > 0)
            h = view.getBottom(); // visible height
        if (b < view.getBottom())
            h -= view.getBottom() - b;
        if (view.getTop() > 0)
            h -= view.getTop();
        int hp = h * 100 / view.getHeight();
        return hp;
    }

    public int findFirstPage() {
        Map<Integer, View> hp15 = new TreeMap<>();
        Map<Integer, View> hp100 = new TreeMap<>();
        Map<Integer, View> hp0 = new TreeMap<>();
        for (int i = 0; i < lm.getChildCount(); i++) {
            View view = lm.getChildAt(i);
            int hp = getViewPercent(view);
            if (hp > 15) // add only views atleast 15% visible
                hp15.put(view.getTop(), view);
            if (hp == 100)
                hp100.put(view.getTop(), view);
            if (hp > 0)
                hp0.put(view.getTop(), view);
        }
        View v = null;
        for (Integer key : hp100.keySet()) {
            v = hp15.get(key);
            break;
        }
        if (v == null) {
            for (Integer key : hp15.keySet()) {
                v = hp15.get(key);
                break;
            }
        }
        if (v == null) {
            for (Integer key : hp15.keySet()) {
                v = hp0.get(key);
                break;
            }
        }
        if (v != null)
            return ((ScrollAdapter.PageView) v).holder.getAdapterPosition();
        return -1;
    }

    int findLastPage() {
        TreeMap<Integer, View> hp0 = new TreeMap<>();
        for (int i = 0; i < lm.getChildCount(); i++) {
            View v = lm.getChildAt(i);
            int hp = getViewPercent(v);
            if (hp > 0)
                hp0.put(v.getTop(), v);
        }
        if (hp0.isEmpty())
            return -1;
        ScrollAdapter.PageView v = (ScrollAdapter.PageView) hp0.lastEntry().getValue();
        return v.holder.getAdapterPosition();
    }

    @Override
    public void startManualScrolling(int x, int y, ZLViewEnums.Direction direction) {
    }

    @Override
    public void scrollManuallyTo(int x, int y) {
    }

    @Override
    public void startAnimatedScrolling(ZLViewEnums.PageIndex pageIndex, int x, int y, ZLViewEnums.Direction direction, int speed) {
        startAnimatedScrolling(pageIndex, direction, speed);
    }

    @Override
    public void startAnimatedScrolling(ZLViewEnums.PageIndex pageIndex, ZLViewEnums.Direction direction, int speed) {
        int pos = findFirstPage();
        if (pos == -1)
            return;
        switch (pageIndex) {
            case next:
                pos++;
                break;
            case previous:
                pos--;
                break;
        }
        if (pos < 0 || pos >= adapter.pages.size())
            return;
        smoothScrollToPosition(pos);
        gesturesListener.pinch.pinchClose();
    }

    @Override
    public void startAnimatedScrolling(int x, int y, int speed) {
    }

    @Override
    public void setScreenBrightness(int percent) {
        gesturesListener.brightness.setScreenBrightness(percent);
        postInvalidate();
    }

    @Override
    public int getScreenBrightness() {
        return gesturesListener.brightness.getScreenBrightness();
    }

    @Override
    public void onDraw(Canvas c) {
        super.onDraw(c);
    }

    @Override
    public void draw(Canvas c) {
        if (adapter.size.w != getWidth() || adapter.size.h != getHeight()) { // reset for textbook and reflow mode only
            adapter.reset();
            gesturesListener.pinch.pinchClose();
        }
        super.draw(c);
        updatePosition();
        drawFooter(c);
        fb.invalidateFooter();
    }

    void updatePosition() { // position can vary depend on which page drawn, restore it after every draw
        int first = findFirstPage();
        if (first == -1)
            return;

        ScrollAdapter.PageCursor c = adapter.pages.get(first);

        ZLTextPosition pos = c.start;
        if (pos == null)
            pos = c.end;
            adapter.open(c);
            if (fb.scrollDelayed != null) {
                if (fb.pluginview != null) {
                    Plugin.Page info = fb.pluginview.getPageInfo(getWidth(), getHeight(), c);
                    for (ScrollAdapter.PageCursor p : adapter.pages) {
                        if (p.start != null && p.start.getParagraphIndex() == fb.scrollDelayed.getParagraphIndex()) {
                            if (fb.scrollDelayed instanceof FBReaderView.ZLTextIndexPosition) {
                                Plugin.View.Selection s = fb.pluginview.select(fb.scrollDelayed, ((FBReaderView.ZLTextIndexPosition) fb.scrollDelayed).end);
                                Plugin.View.Selection.Page page = fb.pluginview.selectPage(fb.scrollDelayed, null, info.w, info.h);
                                Plugin.View.Selection.Bounds bb = s.getBounds(page);
                                s.close();
                                Rect union = SelectionView.union(Arrays.asList(bb.rr));
                                int offset = union.top;
                                scrollBy(0, offset);
                                adapter.oldTurn = pos;
                            } else {
                                int offset = (int) (fb.scrollDelayed.getElementIndex() / info.ratio);
                                scrollBy(0, offset);
                                adapter.oldTurn = pos;
                            }
                            fb.scrollDelayed = null;
                            break;
                        }
                    }
                } else {
                    fb.gotoPosition(fb.scrollDelayed);
                    adapter.oldTurn = pos;
                    fb.scrollDelayed = null;
                }
            }
        if (!pos.equals(adapter.oldTurn) && getScrollState() == SCROLL_STATE_IDLE) {
            fb.onScrollingFinished(ZLViewEnums.PageIndex.current);
            adapter.oldTurn = pos;
        }
    }

    void drawFooter(Canvas c) {
        if (fb.app.Model != null) {
            FBView.Footer footer = fb.app.BookTextView.getFooterArea();
            if (footer == null)
                return;
            ZLAndroidPaintContext context = new ZLAndroidPaintContext(
                    fb.app.SystemInfo,
                    c,
                    new ZLAndroidPaintContext.Geometry(
                            getWidth(),
                            getHeight(),
                            getWidth(),
                            footer.getHeight(),
                            0,
                            getMainAreaHeight()
                    ),
                    0
            );
            int voffset = getHeight() - footer.getHeight();
            c.save();
            c.translate(0, voffset);
            footer.paint(context);
            c.restore();
        }
    }

    public int getMainAreaHeight() {
        final ZLView.FooterArea footer = fb.app.BookTextView.getFooterArea();
        return footer != null ? getHeight() - footer.getHeight() : getHeight();
    }

    public void updateOverlays() {
        for (ScrollAdapter.PageHolder h : adapter.holders)
            overlayUpdate(h.page);
    }

    public void overlayUpdate(ScrollAdapter.PageView view) {
        if (fb.selection != null)
            selectionUpdate(view);
        linksUpdate(view);
        if (view.search != null)
            searchUpdate(view);
    }

    public void linksClose() {
        for (ScrollAdapter.PageHolder h : adapter.holders)
            linksRemove(h.page);
    }

    public void linksRemove(ScrollAdapter.PageView view) {
        if (view.links == null)
            return;
        view.links.close();
        view.links = null;
    }

    public void linksUpdate(ScrollAdapter.PageView view) {
        int pos = view.holder.getAdapterPosition();
        if (pos == -1) {
            linksRemove(view);
        } else {
            ScrollAdapter.PageCursor c = adapter.pages.get(pos);

            final Plugin.View.Selection.Page page;

            if (c.start == null || c.end == null)
                page = null;
            else
                page = fb.pluginview.selectPage(c.start, view.info, view.getWidth(), view.getHeight());
                linksRemove(view);
        }
    }

    @SuppressWarnings("unchecked")
    public void searchPage(int page) {
            for (ScrollAdapter.PageHolder holder : adapter.holders) {
                int pos = holder.getAdapterPosition();
                if (pos != -1) {
                    ScrollAdapter.PageCursor c = adapter.pages.get(pos);
                    if (c.start != null && c.start.getParagraphIndex() == page) {
                        Plugin.View.Selection.Page p = fb.pluginview.selectPage(c.start, holder.page.info, holder.page.getWidth(), holder.page.getHeight());
                        Plugin.View.Search.Bounds bb = fb.search.getBounds(p);
                        if (bb.rr != null) {
                            if (bb.highlight != null) {
                                HashSet hh = new HashSet(Arrays.asList(bb.highlight));
                                for (Rect r : bb.rr) {
                                    if (hh.contains(r)) {
                                        int h = getMainAreaHeight();
                                        int bottom = getTop() + h;
                                        int y = r.top + holder.page.getTop();
                                        if (y > bottom) {
                                            int dy = y - bottom;
                                            int pages = dy / getHeight() + 1;
                                            smoothScrollBy(0, pages * h);
                                        } else {
                                            y = r.bottom + holder.page.getTop();
                                            if (y > bottom) {
                                                int dy = y - bottom;
                                                smoothScrollBy(0, dy);
                                            }
                                        }
                                        y = r.bottom + holder.page.getTop();
                                        if (y < getTop()) {
                                            int dy = y - getTop();
                                            int pages = dy / getHeight() - 1;
                                            smoothScrollBy(0, pages * h);
                                        } else {
                                            y = r.top + holder.page.getTop();
                                            if (y < getTop()) {
                                                int dy = y - getTop();
                                                smoothScrollBy(0, dy);
                                            }
                                        }
                                        searchClose();
                                        updateOverlays();
                                        return;
                                    }
                                }
                            }
                            return;
                        }
                    }
                }
            }
            ZLTextPosition pp = new ZLTextFixedPosition(page, 0, 0);
            fb.gotoPluginPosition(pp);
            fb.resetNewPosition();
    }

    public void searchClose() {
        for (ScrollAdapter.PageHolder h : adapter.holders) {
            searchRemove(h.page);
        }
    }

    public void searchRemove(ScrollAdapter.PageView view) {
        if (view.search == null)
            return;
        view.search.close();
        view.search = null;
    }

    public void searchUpdate(ScrollAdapter.PageView view) {
        int pos = view.holder.getAdapterPosition();
        if (pos == -1) {
            searchRemove(view);
        } else {
            ScrollAdapter.PageCursor c = adapter.pages.get(pos);

            final Plugin.View.Selection.Page page;

            if (c.start == null || c.end == null) {
                page = null;
            } else {
                page = fb.pluginview.selectPage(c.start, view.info, view.getWidth(), view.getHeight());
            }
                searchRemove(view);
        }
    }

    public void selectionClose() {
        for (ScrollAdapter.PageHolder h : adapter.holders)
            selectionRemove(h.page);
    }

    public void selectionRemove(ScrollAdapter.PageView view) {
        if (view.selection != null) {
            fb.selection.remove(view.selection);
            view.selection = null;
        }
    }

    void selectionUpdate(final ScrollAdapter.PageView view) {
        int pos = view.holder.getAdapterPosition();
        if (pos == -1) {
            selectionRemove(view);
        } else {
            ScrollAdapter.PageCursor c = adapter.pages.get(pos);

            boolean selected = true;
            final Plugin.View.Selection.Page page;

            if (c.start == null || c.end == null) {
                selected = false;
                page = null;
            } else {
                page = fb.pluginview.selectPage(c.start, view.info, view.getWidth(), view.getHeight());
            }

            if (selected)
                selected = fb.selection.selection.isSelected(page.page);

            final Rect first;
            final Rect last;

            if (selected) {
                if (view.selection == null) {
                    Plugin.View.Selection.Setter setter = new PDFPlugin.Selection.Setter() {
                        @Override
                        public void setStart(int x, int y) {
                            int pos = NO_POSITION;
                            ScrollAdapter.PageView v = findView(x, y);
                            if (v != null) {
                                pos = v.holder.getAdapterPosition();
                                if (pos != -1) {
                                    ScrollAdapter.PageCursor c = adapter.pages.get(pos);
                                    x = x - v.getLeft();
                                    y = y - v.getTop();
                                    Plugin.View.Selection.Page page = fb.pluginview.selectPage(c.start, v.info, v.getWidth(), v.getHeight());
                                    Plugin.View.Selection.Point point = fb.pluginview.selectPoint(v.info, x, y);
                                    if (point != null)
                                        fb.selection.selection.setStart(page, point);
                                }
                            }
                            selectionUpdate(view);
                            if (pos != -1 && pos != view.holder.getAdapterPosition())
                                selectionUpdate(v);
                        }

                        @Override
                        public void setEnd(int x, int y) {
                            int pos = NO_POSITION;
                            ScrollAdapter.PageView v = findView(x, y);
                            if (v != null) {
                                pos = v.holder.getAdapterPosition();
                                if (pos != -1) {
                                    ScrollAdapter.PageCursor c = adapter.pages.get(pos);
                                    x = x - v.getLeft();
                                    y = y - v.getTop();
                                    Plugin.View.Selection.Page page = fb.pluginview.selectPage(c.start, v.info, v.getWidth(), v.getHeight());
                                    Plugin.View.Selection.Point point = fb.pluginview.selectPoint(v.info, x, y);
                                    if (point != null)
                                        fb.selection.selection.setEnd(page, point);
                                }
                            }
                            selectionUpdate(view);
                            if (pos != -1 && pos != view.holder.getAdapterPosition())
                                selectionUpdate(v);
                        }

                        @Override
                        public Plugin.View.Selection.Bounds getBounds() {
                            Plugin.View.Selection.Bounds bounds = fb.selection.selection.getBounds(page);
                            return bounds;
                        }
                    };
                    view.selection = new SelectionView.PageView(getContext(), (FBReaderView.CustomView) fb.app.BookTextView, setter);
                    fb.selection.add(view.selection);
                }
                int x = view.getLeft();
                int y = view.getTop();
                if (view.info != null)
                    x += view.info.margin.left;
                fb.selection.update(view.selection, x, y);
            } else {
                selectionRemove(view);
            }
        }
    }
}
