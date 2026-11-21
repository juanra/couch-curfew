package cc.nunez.couchcurfew;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A row of bars with a few labels under them. The tallest bar is drawn in the accent color; or, with a guideline, each
 * bar has its own color and a dashed line marks the guideline.
 */
final class Bars extends View {
    private final long[] values;
    private final String[] labels;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color, accent;
    private final float density;
    private int[] colors;
    private long line;
    private String lineLabel;

    /** `labels` has one entry per bar; null leaves that bar without a label. */
    Bars(Context c, long[] values, String[] labels, int color, int accent, int labelColor) {
        super(c);
        this.values = values;
        this.labels = labels;
        this.color = color;
        this.accent = accent;
        density = c.getResources().getDisplayMetrics().density;
        text.setColor(labelColor);
        text.setTextSize(12 * c.getResources().getDisplayMetrics().scaledDensity);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** One color per bar instead of the accent on the tallest, and a dashed line at `line` with `label` at its end. */
    void guideline(int[] colors, long line, String label) {
        this.colors = colors;
        this.line = line;
        lineLabel = label;
    }

    @Override protected void onDraw(Canvas canvas) {
        int n = values.length;
        if (n == 0) return;
        long max = 0;
        int peak = -1;
        for (int i = 0; i < n; i++) if (values[i] > max) { max = values[i]; peak = i; }
        if (colors != null) {
            peak = -1;
            max = Math.max(max, line * 5 / 4); // the line stays inside the chart
        }
        float labelHeight = 20 * density, floor = getHeight() - labelHeight;
        // The guideline's label sits at the end of its line, past the last bar.
        float width0 = getWidth() - (colors == null ? 0 : text.measureText(lineLabel) + 8 * density);
        float slot = width0 / n, gap = Math.min(6 * density, slot * 0.25f), width = Math.min(slot - gap, 24 * density);
        float radius = Math.min(4 * density, width / 2);
        for (int i = 0; i < n; i++) {
            float left = i * slot + (slot - width) / 2;
            // An empty bar still shows as a line, so the axis reads as a whole.
            float height = max == 0 ? 0 : (floor - 2 * density) * values[i] / max;
            bar.setColor(colors != null ? colors[i] : i == peak ? accent : color);
            canvas.drawRoundRect(left, floor - Math.max(height, 2 * density), left + width, floor, radius, radius, bar);
            if (labels[i] == null) continue;
            // Centered under its bar, but never past the edges of the view.
            float half = text.measureText(labels[i]) / 2;
            float x = Math.max(half, Math.min(width0 - half, left + width / 2));
            canvas.drawText(labels[i], x, getHeight() - 4 * density, text);
        }
        if (colors == null) return;
        float y = floor - (floor - 2 * density) * line / max;
        Paint dashes = new Paint(Paint.ANTI_ALIAS_FLAG);
        dashes.setColor(0x99FFFFFF);
        dashes.setStyle(Paint.Style.STROKE);
        dashes.setStrokeWidth(density);
        dashes.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * density, 4 * density}, 0));
        android.graphics.Path path = new android.graphics.Path(); // a path: a dashed drawLine is not drawn on every Android
        path.moveTo(0, y);
        path.lineTo(width0, y);
        canvas.drawPath(path, dashes);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(lineLabel, getWidth(), y + text.getTextSize() / 3, text);
        text.setTextAlign(Paint.Align.CENTER);
    }
}
