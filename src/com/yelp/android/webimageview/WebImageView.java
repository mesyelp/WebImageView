/* Copyright (c) 2012 Yelp Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.android.webimageview;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

public class WebImageView extends ImageView {

	/*
	 * Returns a drawable resourceId if the provided string name
	 * matches an existing drawable resource's name, otherwise
	 * returns 0.
	 * In order to determine the drawable resource from the string name, remove
	 * any filetype suffixes as well as any iOS-style image scaling suffixes
	 * (such as @1-5x). So for example, providing the input string
	 * 'usericon@1-5x.png' would return the resourceId for the drawable
	 * named 'usericon'.
	 */
	public static int getResourceForName(Context context, String filename) {
		if (filename.contains(".")) {
			filename = filename.substring(0, filename.lastIndexOf("."));
		}
		if (filename.contains("@")) {
			filename = filename.substring(0, filename.lastIndexOf("@"));
		}
		return context.getResources().getIdentifier(filename, "drawable", context.getPackageName());
	}


	private String mUrl;
	private Drawable mLoadingDrawable;
	private boolean mLoaded;
	private boolean mSavePermanently;
	private long mPriority;


	public WebImageView(Context context, AttributeSet attributes) {
		super(context, attributes);
		TypedArray array = context.obtainStyledAttributes(attributes, R.styleable.WebImageView);
		if (array != null) {
			applyTypedArray(array);
			array.recycle();
		}
	}

	public void applyTypedArray(TypedArray array) {
		setLoadingDrawable(array.getDrawable(R.styleable.WebImageView_loading));
		mSavePermanently = array.getBoolean(R.styleable.WebImageView_savePermanently, false);
		mPriority = array.getInt(R.styleable.WebImageView_image_priority, 20);
		String url = array.getString(R.styleable.WebImageView_imageUrl);
		boolean autoLoad = array.getBoolean(R.styleable.WebImageView_autoload, !TextUtils.isEmpty(url));
		setImageUrl(url, autoLoad, null);
		invalidate();
	}

	public WebImageView(Context context) {
		super(context);
	}

	/**
	 * Sets whether the image downloaded for this WebImageView should be saved
	 * permanently in application data instead of in a cache directory. Defaults
	 * to false.  Must be set before the image is downloaded.
	 *
	 * @param savePermanently
	 */
	public void setSavePermanently(boolean savePermanently) {
		mSavePermanently = savePermanently;
	}

	/**
	 * Sets the relative priority of the image loading. Lower
	 * values will be favored in the image loading process.
	 * @param priority an integer priority, default is set by the theme or 20
	 */
	public void setPriority(int priority) {
		mPriority = TimeUnit.MILLISECONDS.convert(priority, TimeUnit.SECONDS);
	}

	/**
	 * Sets the WebView's url that its content should be displayed from and
	 * automatically download and display it.
	 * 
	 * @param url the url where the image content is located.
	 */
	public void setImageUrl(String url) {
		setImageUrl(url, 0, 0);
	}

	public void setImageUrl(String url, int reqWidth, int reqHeight) {
		setImageUrl(url, true, null, reqWidth, reqHeight);
	}

	/**
	 * Sets the WebView's url that its content should be displayed from and if
	 * the url is not valid, use the given resource as a placeholder. Very
	 * convenient for when you know the url may be null or empty.
	 * 
	 * @param url the url where the image content is located
	 * @param blank the image resource that should be loaded in the case where
	 *            the url itself is empty.
	 */
	public void setImageUrl(String url, int blank) {
		setImageUrl(url, blank, 0, 0);
	}

	public void setImageUrl(String url, int blank, int reqWidth, int reqHeight) {
		if (TextUtils.isEmpty(url)) {
			setImageResource(blank);
		} else {
			setImageUrl(url, reqWidth, reqHeight);
		}
	}

	public void setImageUrl(String url, boolean load, ImageLoadedCallback callback) {
		setImageUrl(url, load, callback, 0, 0);
	}

	public void setImageUrl(String url, boolean load, ImageLoadedCallback callback, int reqWidth,
			int reqHeight) {
		reset();
		if (!TextUtils.isEmpty(url) && url.startsWith("http")) {
			mUrl = url;
			if (load) {
				loadImage(callback);
			}
		} else if (!TextUtils.isEmpty(url)) {
			if (url.startsWith(ContentResolver.SCHEME_ANDROID_RESOURCE)) {
				// Handle android.resource:// URLs using internal logic
				Uri uri = Uri.parse(url);
				setImageURI(uri);
				return;
			} else if (url.startsWith("file://")) {
				Uri uri = Uri.parse(url);
				String filename = uri.getPath();
				BitmapFactory.Options options = new BitmapFactory.Options();
				if (reqWidth > 0 && reqHeight > 0) {
					options.inSampleSize = calculateInSampleSize(filename, reqWidth, reqHeight);
				}
				if (!setImageFromFile(filename, options)) {
					// Sub-sample once, try again. If that fails, just give up.
					if (options.inSampleSize == 0) {
						options.inSampleSize = 1;
					}
					options.inSampleSize <<= 1;
					setImageFromFile(filename, options);
				}
				return;
			} else if (url.startsWith("bundle://")) {
				url = url.substring("bundle://".length());
			}
			int resource = getResourceForName(getContext(), url);
			if (resource != 0) {
				setImageResource(resource);
			}
		}
	}
	
	private boolean setImageFromFile(String filename, BitmapFactory.Options options) {
		try {
			Bitmap bitmap = BitmapFactory.decodeFile(filename, options);
			setImageBitmap(bitmap);
		} catch (OutOfMemoryError e) {
			return false;
		}
		return true;
	}

	private static int calculateInSampleSize(String filename, int reqWidth, int reqHeight) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(filename, options);
		// Raw height and width of image
		final int height = options.outHeight;
		final int width = options.outWidth;
		int inSampleSize = 1;
		if (height > reqHeight || width > reqWidth) {
			// Calculate ratios of height and width to requested height and
			// width
			final int heightRatio = Math.round((float) height / (float) reqHeight);
			final int widthRatio = Math.round((float) width / (float) reqWidth);
			// Choose the smallest ratio as inSampleSize value, this will
			// guarantee a final image with both dimensions larger than or equal
			// to the requested height and width.
			inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
		}
		return inSampleSize;
	}

	/**
	 * Set the drawable that will be displayed during the time when the image is
	 * not set or is still downloading.
	 * @param d the drawable to be placed as a loading graphic
	 */
	public void setLoadingDrawable(Drawable d) {
		mLoadingDrawable = d;
		if (!mLoaded) {
			setImageDrawable(mLoadingDrawable);
		}
	}

	/**
	 * Set the drawable resource that will be displayed during  the time when
	 * the image is not set or is still downloading
	 * @param res the resource id of the desired loading drawable
	 */
	public void setLoadingDrawable(int res) {
		setLoadingDrawable(getResources().getDrawable(res));
	}

	/**
	 * Blank the image as if it were loading and remove its content
	 */
	public synchronized void reset() {
		if (mLoadingDrawable != null) {
			setImageDrawable(mLoadingDrawable);
		}
		mLoaded = false;
		mUrl = null;
	}

	/**
	 * Load the image content. This normally doesn't need to be called unless
	 * maybe if you wanted to retry downloading an image.
	 */
	public synchronized void loadImage(ImageLoadedCallback callback) {
		if (mUrl == null) {
			throw new IllegalStateException("Cannot load a null Image url");
		}

		if (!mLoaded) {
			setImageDrawable(mLoadingDrawable);
			ImageLoader.start(mUrl, new WebImageLoaderHandler(mUrl, this,
					(Long.MAX_VALUE - SystemClock.elapsedRealtime()) + mPriority, callback), mSavePermanently);
		}
	}

	/**
	 * Tells the caller if the content of the image has already been downloaded
	 * and set.
	 * @return true if content is set, false if otherwise (downloading or failed download)
	 */
	public boolean isLoaded() {
		return mLoaded;
	}

	/**
	 * Directly set the image bitmap of this image and mark
	 * loading as complete for the case of a cache hit.
	 */
	public void setImageBitmap(Bitmap bitmap, boolean finished) {
		setImageBitmap(bitmap);
		mLoaded = finished;
	}

	/**
	 * Image loader handler that makes sure the image's URL matches the image
	 * being applied, to ensure an ImageView isn't set with a stale image.
	 *
	 * @author greg/pretz/alexd
	 */
	public static class WebImageLoaderHandler extends ImageLoaderHandler {
		String mUrl;
		public final WeakReference<WebImageView> mView;
		private final WeakReference<ImageLoadedCallback> mCallback;

		public WebImageLoaderHandler(String url, WebImageView view,
				long priority, ImageLoadedCallback callback) {
			super(view);
			mView = new WeakReference<WebImageView>(view);
			mUrl = url;
			mCallback = new WeakReference<WebImageView.ImageLoadedCallback>(callback);
			super.priority = priority;
		}

		@Override
		public void handleMessage(Message msg) {
			WebImageView view = mView.get();
			if (view == null) {
				return;
			}
			synchronized (view) {
				if (mUrl.equals(view.mUrl)) {
					// This will actually do the image redraw.
					super.handleMessage(msg);
					view.mLoaded = true;
					ImageLoadedCallback cb = mCallback.get();
					if (cb != null) {
						cb.imageLoaded(mView.get());
					}
				}
			}
		}
	}

	public interface ImageLoadedCallback {
		public void imageLoaded(WebImageView view);
	}

}
