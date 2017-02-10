package com.example.tuionf.photowalldemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by tuionf on 2017/2/7.
 */

public class PhotoWallAdapter extends ArrayAdapter<String> implements AbsListView.OnScrollListener {

    private static final String TAG = "PhotoWallAdapter";

    /**
     * 记录所有正在下载或等待下载的任务
     */
    private Set<BitmapWorkerTask> taskCollection;

    /**
     * 图片缓存技术的核心类，用于缓存所有下载好的图片，在程序内存达到设定值时会将最少最近使用的图片移除掉。
     */
    private LruCache<String,Bitmap> mMemoryCache;

    /**
     * GridView的实例
     */
    private GridView mPhotoWall;

    /**
     * 第一张可见图片的下标
     */
    private int mFirstVisibleItem;

    /**
     * 一屏有多少张图片可见
     */
    private int mVisibleItemCount;

    /**
     * 记录是否刚打开程序，用于解决进入程序不滚动屏幕，不会下载图片的问题。
     */
    private boolean isFirstEnter = true;

    public PhotoWallAdapter(Context context, int textViewResourceId, String[] objects,
                            GridView photoWall) {
        super(context, textViewResourceId, objects);
        Log.e(TAG, "PhotoWallAdapter: 构造方法" );
        mPhotoWall = photoWall;
        taskCollection = new HashSet<BitmapWorkerTask>();
        //获取应用程序最大可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        // 设置图片缓存大小为程序最大可用内存的1/8
        int cacheSize = maxMemory/8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // 重写此方法来衡量每张图片的大小，默认返回图片数量
                Log.e(TAG, "PhotoWallAdapter: 构造方法  sizeOf:每张图片的大小 "+ bitmap.getByteCount()/1024);
                return bitmap.getByteCount()/1024;
            }
        };

        mPhotoWall.setOnScrollListener(this);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        String url = getItem(position);
        View view;
        if (convertView == null){
            view = LayoutInflater.from(getContext()).inflate(R.layout.photo_layout,null);
        }else {
            view = convertView;
        }
        ImageView photo = (ImageView) view.findViewById(R.id.photo);
        photo.setTag(url);
        Log.e(TAG, "getView: url::"+url );
        setImageView(url,photo);
        Log.e(TAG, "getView: 中的 setImageView url::"+url );
        return view;
    }

    /*
    * 给ImageView设置图片  首先从Lurcache中取出图片缓存，设置到Imageview上
    * 如果没有缓存，就设置一张默认图片
    *
    * @param url
    *   图片的URL地址，用于作为LruCache的键
    * @param photo
    *   用于显示图片的控件
    * */
    private void setImageView(String url, ImageView photo) {
        Log.e(TAG, "setImageView: " );
        Bitmap bitmap = getBitmapFromMemoryCache(url);
        if (bitmap != null) {
            photo.setImageBitmap(bitmap);
        }else {
            photo.setImageResource(R.drawable.empty_photo);
        }
    }

    /**
     * 将一张图片存储到LruCache中。
     *
     * @param key
     *            LruCache的键，这里传入图片的URL地址。
     * @param bitmap
     *            LruCache的值，这里传入从网络上下载的Bitmap对象。
     */
    public void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        Log.e(TAG, "addBitmapToMemoryCache: 将一张图片存储到LruCache中。");
        if (getBitmapFromMemoryCache(key) == null) {
            mMemoryCache.put(key,bitmap);
        }
    }

        /**
         * 从LruCache中获取一张图片，如果不存在就返回null。
         *
         * @param key
         *            LruCache的键，这里传入图片的URL地址。
         * @return 对应传入键的Bitmap对象，或者null。
         */
    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    @Override
    public void onScroll(AbsListView absListView, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        Log.e(TAG, "onScroll: firstVisibleItem"+firstVisibleItem+"----"+visibleItemCount+"---"+totalItemCount );
        mFirstVisibleItem = firstVisibleItem;
        mVisibleItemCount = visibleItemCount;
        // 下载的任务应该由onScrollStateChanged里调用，但首次进入程序时onScrollStateChanged并不会调用，
        // 因此在这里为首次进入程序开启下载任务。
        if (isFirstEnter && visibleItemCount > 0) {
            loadBitmaps(firstVisibleItem,visibleItemCount);
            isFirstEnter = false;
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView absListView, int i) {

        if (i == SCROLL_STATE_IDLE){
            // 仅当GridView静止时才去下载图片，GridView滑动时取消所有正在下载的任务
            //此时视图不滚动——静止
            loadBitmaps(mFirstVisibleItem, mVisibleItemCount);
        }else {
            cancelAllTasks();
        }
    }

    /**
     * 取消所有正在下载或等待下载的任务。
     */
    public void cancelAllTasks() {
        if (taskCollection != null) {
            for (BitmapWorkerTask task : taskCollection) {
                task.cancel(false);
            }
        }
    }

    /**
     * 加载Bitmap对象。此方法会在LruCache中检查所有屏幕中可见的ImageView的Bitmap对象，
     * 如果发现任何一个ImageView的Bitmap对象不在缓存中，就会开启异步线程去下载图片。
     *
     * @param firstVisibleItem
     *            第一个可见的ImageView的下标
     * @param visibleItemCount
     *            屏幕中总共可见的元素数
     */
    private void loadBitmaps(int firstVisibleItem, int visibleItemCount) {
        Log.e(TAG, "loadBitmaps: 加载Bitmap对象" );
        try {
            for (int i = firstVisibleItem; i < firstVisibleItem + visibleItemCount; i++) {
                String imageUrl = Images.imageThumbUrls[i];
                Bitmap bitmap = getBitmapFromMemoryCache(imageUrl);
                if (bitmap == null) {
                    Log.e(TAG, "loadBitmaps: 加载Bitmap对象——异步下载" );
                    BitmapWorkerTask task = new BitmapWorkerTask();
                    taskCollection.add(task);
                    task.execute(imageUrl);
                }else {
                    //TODO
                    ImageView imageView = (ImageView) mPhotoWall.findViewWithTag(imageUrl);
                    if (imageView != null && bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 异步下载图片的任务。
     *
     * @author guolin
     */
    class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap> {

        /**
         * 图片的URL地址
         */
        private String imageUrl;

        @Override
        protected Bitmap doInBackground(String... strings) {

            Log.e(TAG, "doInBackground: 后台下载图片" );
            imageUrl = strings[0];
            //后台下载图片
            Bitmap bitmap = downloadBitmap(strings[0]);
            if (bitmap != null) {
                addBitmapToMemoryCache(strings[0],bitmap);
            }
            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            // 根据Tag找到相应的ImageView控件，将下载好的图片显示出来。
            ImageView imageView = (ImageView) mPhotoWall.findViewWithTag(imageUrl);
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
            taskCollection.remove(this);

            Log.e(TAG, "onPostExecute:根据Tag找到相应的ImageView控件，将下载好的图片显示出来 "+imageUrl );
        }

        /**
         * 建立HTTP请求，并获取Bitmap对象。
         *
         * @param imageUrl
         *            图片的URL地址
         * @return 解析后的Bitmap对象
         */
        private Bitmap downloadBitmap(String imageUrl) {

            Log.e(TAG, "downloadBitmap: 启动网络请求下载图片");
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(imageUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                bitmap = BitmapFactory.decodeStream(connection.getInputStream());
            }catch (Exception e) {
                e.printStackTrace();
            }finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return bitmap;
        }
        }
}
