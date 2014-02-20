package io.kickflip.sdk.av;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

import io.kickflip.sdk.FileUtils;
import io.kickflip.sdk.api.KickflipCallback;
import io.kickflip.sdk.HlsFileObserver;
import io.kickflip.sdk.api.KickflipApiClient;
import io.kickflip.sdk.api.json.HlsStream;
import io.kickflip.sdk.api.json.Response;
import io.kickflip.sdk.api.json.User;
import io.kickflip.sdk.api.s3.S3Manager;
import io.kickflip.sdk.api.s3.S3Upload;
import io.kickflip.sdk.events.BroadcastIsLiveEvent;
import io.kickflip.sdk.events.MuxerFinishedEvent;
import io.kickflip.sdk.events.HlsManifestWrittenEvent;
import io.kickflip.sdk.events.HlsSegmentWrittenEvent;
import io.kickflip.sdk.events.S3UploadEvent;
import io.kickflip.sdk.events.UploadEvent;
import io.kickflip.sdk.api.s3.S3Client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Broadcaster is an AVRecorder that broadcasts its output
 * to Kickflip.
 */
// TODO: Make HLS / RTMP Agnostic
public class Broadcaster extends AVRecorder {
    private static final String TAG = "Broadcaster";
    private static final boolean VERBOSE = true;

    private KickflipApiClient mKickflip;
    private User mUser;
    private HlsStream mStream;
    private HlsFileObserver mFileObserver;
    private S3Client mS3Client;
    private ArrayDeque<Pair<String, File>> mUploadQueue;
    private RecorderConfig mConfig;
    private EventBus mEventBus;
    private boolean mReadyToBroadcast;                                  // Kickflip user registered and endpoint ready
    private boolean mSentBroadcastLiveEvent;
    private int mVideoBitrate;
    private File mManifestSnapshotDir;                                  // Directory where manifest snapshots are stored
    private File mMasterManifest;                                       // Master HLS Manifest containing complete history
    private int mNumSegmentsWritten;
    private int mLastManifestLength;

    private static final int MIN_BITRATE = 3 * 100 * 1000;              // 300 kbps


    public Broadcaster(Context context, RecorderConfig config, String API_KEY, String API_SECRET) {
        super(config);
        checkArgument(API_KEY != null && API_SECRET != null);
        mLastManifestLength = 0;
        mNumSegmentsWritten = 0;
        mSentBroadcastLiveEvent = false;
        mEventBus = new EventBus("Broadcaster");
        mEventBus.register(this);
        mConfig = config;
        mConfig.getMuxer().setEventBus(mEventBus);
        mVideoBitrate = mConfig.getVideoBitrate();
        Log.i(TAG, "Initial video bitrate : " + mVideoBitrate);
        mManifestSnapshotDir = new File(mConfig.getOutputPath().substring(0, mConfig.getOutputPath().lastIndexOf("/")+1), "m3u8");
        mManifestSnapshotDir.mkdir();
        mMasterManifest = new File(mManifestSnapshotDir, "master.m3u8");
        writeMasterManifestHeader();

        String watchDir = config.getOutputPath().substring(0, config.getOutputPath().lastIndexOf(File.separator)+1);
        mFileObserver = new HlsFileObserver(watchDir, mEventBus);
        mFileObserver.startWatching();

        mReadyToBroadcast = false;
        mKickflip = new KickflipApiClient(context, API_KEY, API_SECRET, new KickflipCallback() {
            @Override
            public void onSuccess(Response response) {
                User user = (User) response;
                mUser = user;
                if (VERBOSE) Log.i(TAG, "Got storage credentials " + response);
            }

            @Override
            public void onError(Object response) {
                Log.e(TAG, "Failed to get storage credentials" + response.toString());
            }
        });
    }

    public EventBus getEventBus(){
        return mEventBus;
    }

    @Override
    public void startRecording(){
        super.startRecording();
        mKickflip.startStream(new KickflipCallback() {
            @Override
            public void onSuccess(Response response) {
                checkArgument(response instanceof HlsStream);
                mStream = (HlsStream) response;
                if (VERBOSE) Log.i(TAG, "Got hls start stream response " + response);
                mS3Client = new S3Client(mStream.getBasicAWSCredentials(), mEventBus);
                mS3Client.setBucket(mStream.getBucket());
                mReadyToBroadcast = true;
                submitQueuedUploadsToS3();
            }

            @Override
            public void onError(Object response) {
                Log.w(TAG, "Error getting start stream response! " + response);
            }
        });
    }

    public boolean isLive(){
        return mSentBroadcastLiveEvent;
    }

    @Override
    public void stopRecording(){
        super.stopRecording();
        if(mStream != null){
            mKickflip.stopStream(mUser, mStream, new KickflipCallback() {
                @Override
                public void onSuccess(Response response) {
                    if (VERBOSE) Log.i(TAG, "Got stop stream response " + response);
                }

                @Override
                public void onError(Object response) {
                    Log.w(TAG, "Error getting stop stream response! " + response);
                }
            });
        }
    }

    @Subscribe
    public void onS3UploadComplete(S3UploadEvent uploadEvent){
        if (VERBOSE) Log.i(TAG, "Upload completed for " + uploadEvent.getUrl());
        if(uploadEvent.getUrl().contains(".m3u8")){
            onManifestUploaded(uploadEvent);
        } else if(uploadEvent.getUrl().contains(".ts")){
            onSegmentUploaded(uploadEvent);
        }
    }

    public void onSegmentUploaded(S3UploadEvent uploadEvent){
        try{
            if(Build.VERSION.SDK_INT >= 19){
                // Adjust video encoder bitrate per bandwidth of just-completed upload
                if (VERBOSE) Log.i(TAG, "Bandwidth: " + uploadEvent.getUploadByteRate() + " Bps Birate: " + ((mVideoBitrate + mConfig.getAudioBitrate()) / 8) + " Bps");
                if( uploadEvent.getUploadByteRate() < (((mVideoBitrate + mConfig.getAudioBitrate()) / 8) )){
                    // The new bitrate is equal to the last upload bandwidth, never exceeding MIN_BITRATE, or the
                    // bitrate intially provided in RecorderConfig
                    mVideoBitrate = Math.max(Math.min(uploadEvent.getUploadByteRate(), mConfig.getVideoBitrate()), MIN_BITRATE);
                    if (VERBOSE) Log.i(TAG, String.format("Adjusting encoder bitrate. Bandwidth: %d, Bitrate: %d, New Bitrate: %d",
                            uploadEvent.getUploadByteRate(), mConfig.getTotalBitrate(), mVideoBitrate ));
                    adjustBitrate(mVideoBitrate);
                }
            }
        } catch (Exception e){
                Log.i(TAG, "OnSegUpload excep");
                e.printStackTrace();
            }
    }

    public void onManifestUploaded(S3UploadEvent uploadEvent){
        if(!mSentBroadcastLiveEvent){
            mEventBus.post(new BroadcastIsLiveEvent(((HlsStream) mStream).getKickflipUrl()));
            mSentBroadcastLiveEvent = true;
        }
    }

    @Subscribe
    public void onDeadEvent(DeadEvent e){
        if (VERBOSE) Log.i(TAG, "DeadEvent ");
    }

    @Subscribe
    public void onManifestUpdated(HlsManifestWrittenEvent e){
        if (VERBOSE) Log.i(TAG, "onManifestUpdated");
        // Copy m3u8 at this moment and queue it to uploading
        // service
        final File orig = new File(e.getManifestLocation());
        final File copy = new File(mManifestSnapshotDir, orig.getName()
                .replace(".m3u8", "_" + mNumSegmentsWritten + ".m3u8"));
        try {
            FileUtils.copy(orig, copy);
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        appendLastManifestEntryToMasterManifest(orig, !isRecording());
        Log.i(TAG, "m3u8 dst: " + copy.getAbsolutePath());
        queueOrSubmitUpload(keyForFilename("index.m3u8"), copy.getAbsolutePath());
        mNumSegmentsWritten++;
    }

    @Subscribe
    public void onSegmentWritten(HlsSegmentWrittenEvent e){
        if (VERBOSE) Log.i(TAG, "onSegmentWritten");
        String fileName = e.getSegmentLocation().substring(e.getSegmentLocation().lastIndexOf(File.separator) + 1);
        queueOrSubmitUpload(keyForFilename(fileName), e.getSegmentLocation());
    }

    @Subscribe
    public void onMuxerFinished(MuxerFinishedEvent e){
        // TODO: Broadcaster uses AVRecorder reset()
        // this seems better than nulling and recreating Broadcaster
        // since it should be usable as a static object for
        // bg recording
    }

    private String keyForFilename(String fileName){
        return mUser.getName() + File.separator
                + mStream.getStreamId() + File.separator
                + fileName;
    }

    /**
     * Handle an upload, either submitting to the S3 client
     * or queueing for submission once credentials are ready
     * @param key destination key
     * @param fileLocation local file
     */
    private void queueOrSubmitUpload(String key, String fileLocation){
        if(mReadyToBroadcast){
            S3Manager.queueUpload(new S3Upload(mS3Client, new File(fileLocation), key));
        }else{
            if (VERBOSE) Log.i(TAG, "queueing " + key);
            queueUpload(key, fileLocation);
        }
    }

    /**
     * Queue an upload for later submission to S3
     * @param key destination key
     * @param fileLocation local file
     */
    private void queueUpload(String key, String fileLocation){
        if(mUploadQueue == null)
            mUploadQueue = new ArrayDeque<>();
        mUploadQueue.add(new Pair<>(key, new File(fileLocation)));
    }

    /**
     * Submit all queued uploads to the S3 client
     */
    private void submitQueuedUploadsToS3(){
        if(mUploadQueue == null) return;
        for(Pair<String, File> pair : mUploadQueue){
            S3Manager.queueUpload(new S3Upload(mS3Client, pair.second, pair.first));
        }
    }

    private void writeMasterManifestHeader(){
        FileUtils.writeStringToFile("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-TARGETDURATION:12\n",
                mMasterManifest, false);
    }

    private void appendLastManifestEntryToMasterManifest(File sourceManifest, boolean lastEntry){
        String result = FileUtils.tail2(sourceManifest, lastEntry ? 3: 2);
        if(lastEntry) Log.i(TAG, "Appending last manifest entry");
        Log.i(TAG, "Appending to Master Manifest: " + result);
        FileUtils.writeStringToFile(result, mMasterManifest, true);
        if(lastEntry)
            S3Manager.queueUpload(new S3Upload(mS3Client, mMasterManifest, keyForFilename("index.m3u8")));
    }

}
