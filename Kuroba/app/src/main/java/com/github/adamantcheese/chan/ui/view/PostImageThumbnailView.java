/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.ui.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import com.github.adamantcheese.chan.R;
import com.github.adamantcheese.chan.core.model.PostImage;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.utils.AndroidUtils;

import static com.github.adamantcheese.chan.utils.AndroidUtils.getDimen;

public class PostImageThumbnailView extends ThumbnailView implements View.OnLongClickListener {
    private PostImage postImage;
    private Drawable playIcon;
    private Rect bounds = new Rect();
    private float ratio = 0f;

    public PostImageThumbnailView(Context context) {
        this(context, null);
    }

    public PostImageThumbnailView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PostImageThumbnailView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.setOnLongClickListener(this);

        playIcon = context.getDrawable(R.drawable.ic_play_circle_outline_white_24dp);
    }

    public void setPostImage(PostImage postImage, boolean useHiRes) {
        if (this.postImage != postImage) {
            this.postImage = postImage;
            int thumbnailSize = getDimen(getContext(), R.dimen.cell_post_thumbnail_size);

            if (postImage != null) {
                String url = postImage.getThumbnailUrl().toString();
                if (ChanSettings.autoLoadThreadImages.get() && useHiRes) {
                    if (!postImage.spoiler || ChanSettings.revealImageSpoilers.get()) {
                        url = postImage.type == PostImage.Type.STATIC ? postImage.imageUrl.toString() : postImage.getThumbnailUrl().toString();
                    }
                }
                //500 is big enough for it to be high enough quality to be noticable and not rescale a lot
                //but also not kill on memory usage (250 4K images at native would be 4GB RAM, rescaled it is 62.5MB)
                setUrl(url, useHiRes && ChanSettings.autoLoadThreadImages.get() ? 500 : thumbnailSize,
                        useHiRes && ChanSettings.autoLoadThreadImages.get() ? 500 : thumbnailSize);
            } else {
                setUrl(null);
            }
        }
    }

    public void setRatio(float ratio) {
        this.ratio = ratio;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (postImage != null && postImage.type == PostImage.Type.MOVIE && !error) {
            int x = (int) (getWidth() / 2.0 - playIcon.getIntrinsicWidth() / 2.0);
            int y = (int) (getHeight() / 2.0 - playIcon.getIntrinsicHeight() / 2.0);

            bounds.set(x, y, x + playIcon.getIntrinsicWidth(), y + playIcon.getIntrinsicHeight());
            playIcon.setBounds(bounds);
            playIcon.draw(canvas);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (ratio == 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY && (heightMode == MeasureSpec.UNSPECIFIED || heightMode == MeasureSpec.AT_MOST)) {
                int width = MeasureSpec.getSize(widthMeasureSpec);

                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (width / ratio), MeasureSpec.EXACTLY));
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (postImage == null || postImage.imageUrl == null) {
            return false;
        }

        ClipboardManager clipboard = (ClipboardManager) AndroidUtils.getAppContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assert clipboard != null;
        ClipData clip = ClipData.newPlainText("File url", postImage.imageUrl.toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(getContext(), R.string.file_url_copied_to_clipboard, Toast.LENGTH_SHORT).show();

        return true;
    }
}
