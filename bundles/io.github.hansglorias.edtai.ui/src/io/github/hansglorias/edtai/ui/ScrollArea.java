package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Listener;

/** Прокручиваемая область без системных полос: панели, формы и настройки. */
class ScrollArea extends Composite {
    private Control content;
    private Point minimum = new Point(0, 0);
    private Point origin = new Point(0, 0);
    private boolean expandHorizontal;
    private boolean expandVertical;
    private boolean showFocusedControl;
    private final boolean across;
    private boolean arranging;
    private final ThinScrollBar vertical;
    private final ThinScrollBar horizontal;
    private final Listener wheel = event -> {
        if (!(event.widget instanceof Control control) || event.count == 0 || !event.doit || !owns(control, event.count)) return;
        setOrigin(origin.x, origin.y - event.count * 20);
        event.doit = false;
    };
    private final Listener focus = event -> {
        if (!showFocusedControl || !(event.widget instanceof Control control) || !inside(control)) return;
        showControl(control);
    };
    private final Listener acrossWheel = this::acrossWheel;

    ScrollArea(Composite parent, int style) {
        super(parent, style & ~(SWT.V_SCROLL | SWT.H_SCROLL));
        across = (style & SWT.H_SCROLL) != 0;
        vertical = new ThinScrollBar(this, false, model(false));
        horizontal = new ThinScrollBar(this, true, model(true));
        setLayout(new Layout() {
            protected Point computeSize(Composite parent, int w, int h, boolean changed) {
                Point size = content == null ? minimum : content.computeSize(w == SWT.DEFAULT ? w : Math.max(1, w - ThinScrollBar.WIDTH), h, changed);
                return new Point(w == SWT.DEFAULT ? size.x + ThinScrollBar.WIDTH : w,
                        h == SWT.DEFAULT ? size.y : h);
            }
            protected void layout(Composite parent, boolean changed) { arrange(); }
        });
        getDisplay().addFilter(SWT.MouseVerticalWheel, wheel);
        getDisplay().addFilter(SWT.MouseHorizontalWheel, acrossWheel);
        getDisplay().addFilter(SWT.FocusIn, focus);
        addListener(SWT.Dispose, event -> {
            getDisplay().removeFilter(SWT.MouseVerticalWheel, wheel);
            getDisplay().removeFilter(SWT.MouseHorizontalWheel, acrossWheel);
            getDisplay().removeFilter(SWT.FocusIn, focus);
        });
        addListener(SWT.Resize, event -> arrange());
    }

    private ThinScrollBar.Model model(boolean across) {
        return new ThinScrollBar.Model() {
            public int total() { return content == null ? 0 : across ? content.getSize().x : content.getSize().y; }
            public int page() { return across ? viewport().width : viewport().height; }
            public int position() { return across ? origin.x : origin.y; }
            public void scroll(int position) { setOrigin(across ? position : origin.x, across ? origin.y : position); }
            public int increment() { return 20; }
        };
    }

    Rectangle viewport() {
        Rectangle area = getClientArea();
        return new Rectangle(0, 0, Math.max(0, area.width - ThinScrollBar.WIDTH),
                Math.max(0, area.height - (across ? ThinScrollBar.WIDTH : 0)));
    }

    private void arrange() {
        if (content == null || content.isDisposed() || arranging) return;
        arranging = true;
        try {
            Rectangle area = viewport();
            int width = !across ? area.width : expandHorizontal ? Math.max(minimum.x, area.width) : Math.max(minimum.x, content.getSize().x);
            int height = expandVertical ? Math.max(minimum.y, area.height) : Math.max(minimum.y, content.getSize().y);
            origin.x = Math.max(0, Math.min(origin.x, width - area.width));
            origin.y = Math.max(0, Math.min(origin.y, height - area.height));
            Rectangle bounds = new Rectangle(-origin.x, -origin.y, width, height);
            if (!content.getBounds().equals(bounds)) content.setBounds(bounds);
            vertical.control().setBounds(area.width, 0, ThinScrollBar.WIDTH, area.height);
            horizontal.control().setBounds(0, area.height, area.width, ThinScrollBar.WIDTH);
            vertical.control().moveAbove(null);
            horizontal.control().moveAbove(null);
            vertical.update();
            horizontal.update();
        } finally { arranging = false; }
    }

    private boolean inside(Control control) {
        for (Control item = control; item != null; item = item.getParent()) if (item == this) return true;
        return false;
    }

    private boolean owns(Control control, int count) {
        if (control == vertical.control() || control == horizontal.control()) return false;
        for (Control item = control; item != null; item = item.getParent()) {
            if (item == this) return true;
            if (item instanceof ScrollArea || item instanceof ScrollFrame scroll && scroll.canScroll(count)) return false;
        }
        return false;
    }

    private void acrossWheel(org.eclipse.swt.widgets.Event event) {
        if (!across || event.count == 0 || !event.doit || !(event.widget instanceof Control control)
                || control == horizontal.control() || !inside(control)) return;
        for (Control item = control; item != this; item = item.getParent()) {
            if (item instanceof ScrollArea area && area.horizontal().isShown()) return;
            if (item instanceof ScrollFrame frame && frame.horizontal().isShown()) return;
        }
        setOrigin(origin.x - event.count * 20, origin.y);
        event.doit = false;
    }

    void setContent(Control value) { content = value; arrange(); }
    Control getContent() { return content; }
    void setMinSize(Point size) { setMinSize(size.x, size.y); }
    void setMinSize(int width, int height) {
        Point next = new Point(Math.max(0, width), Math.max(0, height));
        if (next.equals(minimum)) return;
        minimum = next;
        arrange();
    }
    int getMinHeight() { return minimum.y; }
    void setMinHeight(int height) { setMinSize(minimum.x, height); }
    int getMinWidth() { return minimum.x; }
    void setExpandHorizontal(boolean value) { expandHorizontal = value; arrange(); }
    void setExpandVertical(boolean value) { expandVertical = value; arrange(); }
    void setShowFocusedControl(boolean value) { showFocusedControl = value; }
    void showControl(Control control) {
        if (!inside(control)) return;
        Point at = toControl(control.toDisplay(0, 0));
        Rectangle page = viewport();
        int x = at.x < 0 ? origin.x + at.x : at.x + control.getSize().x > page.width
                ? origin.x + at.x + control.getSize().x - page.width : origin.x;
        int y = at.y < 0 ? origin.y + at.y : at.y + control.getSize().y > page.height
                ? origin.y + at.y + control.getSize().y - page.height : origin.y;
        setOrigin(x, y);
    }
    Point getOrigin() { return new Point(origin.x, origin.y); }
    void setOrigin(int x, int y) { origin = new Point(x, y); arrange(); }
    ThinScrollBar vertical() { return vertical; }
    ThinScrollBar horizontal() { return horizontal; }
}
