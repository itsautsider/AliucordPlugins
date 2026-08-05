package com.aliucord.plugins;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.aliucord.Constants;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.utils.DimenUtils;
import com.aliucord.utils.ReflectUtils;
import com.aliucord.wrappers.ChannelWrapper;
import com.discord.stores.StoreStageInstances;
import com.discord.stores.StoreStream;
import com.discord.stores.StoreThreadsJoined;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.permissions.PermissionUtils;
import com.discord.widgets.chat.input.WidgetChatInputEditText$setOnTextChangedListener$1;
import com.lytefast.flexinput.fragment.FlexInputFragment;
import com.lytefast.flexinput.widget.FlexEditText;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@SuppressWarnings("unused")
@AliucordPlugin
public class VoiceMessages extends Plugin {
    WaveFormView waveFormView;
    FlexEditText editText;
    ImageButton recordButton;
    ViewGroup inputContainer;
    File outputFile;
    private MediaRecorder mediaRecorder;
    private volatile boolean isRecording;
    private volatile boolean locked;
    private final Runnable updateWaveform = () -> {

        while (isRecording && !Thread.currentThread().isInterrupted()) {
            try {
                waveFormView.addWave((int) ((mediaRecorder.getMaxAmplitude() / 32767.0) * 254) + 1); //discord uses 8 bit , explode. Also I add 1 because if we insert 0 it breaks discorc
            } catch (RuntimeException ignored) {
                break;
            }
            waveFormView.postInvalidate();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };
    private Thread updateWaveformThread;
    public static SettingsAPI staticSettings;
    long voicePermissionMask = ((long) 1 << 46);
    long adminMask = ((long) 1 << 3);
    long meID;
    StoreStageInstances stageInstances;
    StoreThreadsJoined storeThreadsJoined;
    int outputFormat = MediaRecorder.OutputFormat.OGG;
    int audioEncoder = MediaRecorder.AudioEncoder.OPUS;
    String extension = ".ogg";

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void start(Context context) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            outputFormat = MediaRecorder.OutputFormat.MPEG_2_TS;
            audioEncoder = MediaRecorder.AudioEncoder.AAC;
            extension = ".aac";
        }

        stageInstances = (StoreStageInstances) ReflectUtils.getField(StoreStream.getPermissions(), "storeStageInstances");
        storeThreadsJoined = (StoreThreadsJoined) ReflectUtils.getField(StoreStream.getPermissions(), "storeThreadsJoined");
        meID = StoreStream.getUsers().getMe().getId();

        staticSettings = settings;

        if (settings.getString("vendorId", null) == null) {
            settings.setString("vendorId", UUID.randomUUID().toString());
        }

        settingsTab = new SettingsTab(BottomShit.class, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings);
        waveFormView = new WaveFormView(context);
        recordButton = new ImageButton(context);

        var drawable = ContextCompat.getDrawable(context, com.lytefast.flexinput.R.e.ic_mic_grey_24dp);
        drawable.setTint(ColorCompat.getColor(context, com.lytefast.flexinput.R.c.primary_dark_300));
        recordButton.setImageDrawable(drawable);
        recordButton.setBackgroundColor(Color.TRANSPARENT);

        mediaRecorder = new MediaRecorder();

        recordButton.setOnTouchListener((view, motionEvent) -> {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // In locked mode a tap stops and sends the recording
                    if (isRecording && locked) {
                        onRecordStop(true, StoreStream.getChannelsSelected().getId());
                        return true;
                    }
                    try {
                        onRecordStart();
                    } catch (IOException e) {
                        logger.error(e);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isRecording && !locked) {
                        if (motionEvent.getX() < -DimenUtils.dpToPx(80)) {
                            // swipe left -> discard
                            onRecordStop(false, 0L);
                            Utils.showToast("Cancelled recording");
                        } else if (motionEvent.getY() < -DimenUtils.dpToPx(60)) {
                            // swipe up -> lock, so the user can let go
                            locked = true;
                            Utils.showToast("Recording locked - tap to send");
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (isRecording && !locked) {
                        onRecordStop(true, StoreStream.getChannelsSelected().getId());
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    // Touch stream got killed (e.g. layout rebuilt underneath us).
                    // Never leave the recorder running, that's what caused the
                    // "invisible locked button" bug.
                    if (isRecording && !locked) {
                        onRecordStop(false, 0L);
                        Utils.showToast("Cancelled recording");
                    }
                    return true;
            }
            return false;
        });


        try {
            recordButton.setVisibility(StoreStream.getChannelsSelected().getSelectedChannel().i() == 0L ? View.VISIBLE : View.GONE);
        } catch (NullPointerException ignored) {
            // if no channel is selected plugin will throw error
        }

        patcher.patch(FlexInputFragment.class.getDeclaredMethod("onViewCreated", View.class, Bundle.class), cf -> {
            var input = (FlexInputFragment) cf.thisObject;

            editText = input.getView().findViewById(Utils.getResId("text_input", "id"));

            inputContainer = (ViewGroup) input.getView().findViewById(Utils.getResId("main_input_container", "id"));

            attachViews();

            // Other plugins (e.g. ActivitiesV2) and themes may rebuild the input
            // container after us, which silently drops our views. Re-attach a few
            // times while everything is still loading.
            Utils.mainThread.postDelayed(this::attachViews, 1000);
            Utils.mainThread.postDelayed(this::attachViews, 3000);
            Utils.mainThread.postDelayed(this::attachViews, 6000);
        });

        patcher.patch(WidgetChatInputEditText$setOnTextChangedListener$1.class.getDeclaredMethod("afterTextChanged", Editable.class), cf -> {
            if (editText == null || recordButton == null) {
                return;
            }

            attachViews();

            if (editText.getText() == null || editText.getText().toString().equals("")) {
                var selectedChannel = StoreStream.getChannelsSelected().getSelectedChannel();
                if (selectedChannel == null) {
                    setRecordButtonVisibility(View.GONE);
                    return;
                }

                ChannelWrapper channel = new ChannelWrapper(selectedChannel);
                logger.info(String.valueOf(channel.getId()));
                showVoiceChannelIconIfCan(channel.getId());
            } else {
                recordButton.setVisibility(View.GONE);
            }
        });


        patcher.patch(StoreStream.class.getDeclaredMethod("handleChannelSelected", long.class), cf -> {
            var id = (long) cf.args[0];

            if (id != 0L) {
                Utils.mainThread.post(this::attachViews);
                try {
                    showVoiceChannelIconIfCan(id);
                } catch (NullPointerException e) {
                    logger.error(e);
                }
            }
        });
    }

    /**
     * Makes sure the waveform view and the record button are children of the
     * *current* input container. Safe to call as often as you like: it only
     * touches the view tree when something actually got detached.
     * Must run on the main thread.
     */
    private void attachViews() {
        if (inputContainer == null || recordButton == null || waveFormView == null) {
            return;
        }

        // Never re-attach while recording: removeView() kills the ongoing touch
        // stream, so ACTION_UP never arrives and the recording can't be stopped.
        if (isRecording) {
            return;
        }

        try {
            if (waveFormView.getParent() != inputContainer) {
                detachFromParent(waveFormView);
                inputContainer.addView(waveFormView, 0);
                var params = (LinearLayout.LayoutParams) waveFormView.getLayoutParams();
                params.height = DimenUtils.dpToPx(30);
                params.gravity = Gravity.CENTER;

                // In a horizontal container a full width waveform pushes the
                // record button off screen. Share the row instead.
                if (inputContainer instanceof LinearLayout
                        && ((LinearLayout) inputContainer).getOrientation() == LinearLayout.HORIZONTAL) {
                    params.width = 0;
                    params.weight = 1f;
                }
                waveFormView.setLayoutParams(params);

                waveFormView.setVisibility(isRecording ? View.VISIBLE : View.GONE);
            }

            if (recordButton.getParent() != inputContainer) {
                detachFromParent(recordButton);
                inputContainer.addView(recordButton);

                // Re-apply the correct visibility for the channel we are in
                if (editText != null && editText.getText() != null && !editText.getText().toString().equals("")) {
                    recordButton.setVisibility(View.GONE);
                } else {
                    var selectedId = StoreStream.getChannelsSelected().getId();
                    if (selectedId != 0L) {
                        showVoiceChannelIconIfCan(selectedId);
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.error(e);
        }
    }

    private void detachFromParent(View view) {
        if (view == null) {
            return;
        }

        ViewParent parent = view.getParent();

        if (parent instanceof ViewGroup) {
            var group = (ViewGroup) parent;
            group.endViewTransition(view);
            group.removeView(view);
        }
    }

    public void onRecordStart() throws IOException {
        if (isRecording) {
            return;
        }

        //check permission
        if (ContextCompat.checkSelfPermission(Utils.getAppContext(), android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(Utils.getAppActivity(), new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);

            return;
        }

        waveFormView.reset();

        // prepare media recorder
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(outputFormat);
        mediaRecorder.setAudioEncoder(audioEncoder);

        mediaRecorder.setAudioEncodingBitRate(settings.getInt("audioQuality", 128) * 1024);
        mediaRecorder.setAudioSamplingRate(settings.getBool("highSamplingRate", false) ? 48000 : 44100);

        outputFile = File.createTempFile("audio_record", extension, new File(Constants.BASE_PATH));
        outputFile.deleteOnExit();
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
        } catch (IOException e) {
            mediaRecorder.reset();
            throw e;
        } catch (RuntimeException e) {
            mediaRecorder.reset();
            throw e;
        }

        editText.setVisibility(View.GONE);
        waveFormView.setVisibility(View.VISIBLE);

        updateWaveformThread = new Thread(updateWaveform);
        updateWaveformThread.start();

    }

    public void onRecordStop(boolean send, long discordid) {
        if (!isRecording) {
            return;
        }

        isRecording = false;
        locked = false;
        if (updateWaveformThread != null && updateWaveformThread.isAlive()) {
            updateWaveformThread.interrupt();
        }

        File recordedFile = outputFile;
        String recordingExtension = extension;
        String waveform = waveFormView.getWaveForm();
        boolean stopped = false;

        try {
            mediaRecorder.stop();
            stopped = true;
        } catch (RuntimeException e) {
            // if you instantly stop recording it causes crash
            logger.error(e);
        }

        mediaRecorder.reset();

        waveFormView.setVisibility(View.GONE);
        editText.setVisibility(View.VISIBLE);

        // Layout may have been rebuilt while we were recording - restore now
        // that no touch gesture is in flight.
        Utils.mainThread.post(this::attachViews);

        if (send && stopped && recordedFile != null) {
            Utils.threadPool.execute(() -> {
                try {
                    var filename = DiscordAPI.uploadFile(recordedFile, discordid, recordingExtension);
                    float seconds = getRecordingDurationSeconds(recordedFile);
                    DiscordAPI.sendVoiceMessage(filename, seconds, waveform, discordid, recordingExtension);
                } catch (RuntimeException e) {
                    logger.error(e);
                    Utils.showToast("Failed to send voice message");
                }
            });
        }
    }

    private float getRecordingDurationSeconds(File file) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();

        try {
            mmr.setDataSource(file.getAbsolutePath());
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (durationStr == null) {
                return 0.0f;
            }

            return Integer.parseInt(durationStr) / 1000.0f;
        } catch (RuntimeException e) {
            logger.error(e);
            return 0.0f;
        } finally {
            try {
                mmr.release();
            } catch (RuntimeException ignored) {}
        }
    }

    public void showVoiceChannelIconIfCan(long channelId) {

        var rawChannel = StoreStream.getChannels().getChannel(channelId);
        if (rawChannel == null) {
            setRecordButtonVisibility(View.GONE);
            return;
        }

        var channel = new ChannelWrapper(rawChannel);
        var guild = StoreStream.getGuilds().getGuild(channel.getGuildId());
        if (channel.isDM()) {
            // if channel is dm it causes guild to be null and causes issues
            setRecordButtonVisibility(View.VISIBLE);
            return;
        }
        if (guild == null) {
            setRecordButtonVisibility(View.GONE);
            return;
        }

        var permissions = PermissionUtils.computePermissions(meID,
                rawChannel,
                StoreStream.getChannels().getGuildChannelInternal$app_productionGoogleRelease(guild.getId(), channel.getParentId()),
                guild.getOwnerId(),
                StoreStream.getGuilds().getMember(guild.getId(), meID),
                StoreStream.getGuilds().getRoles().get(guild.getId()),
                stageInstances.getStageInstancesForGuild(guild.getId()),
                storeThreadsJoined.hasJoinedInternal(channel.getId())
        );

        var admin = PermissionUtils.can(adminMask, permissions);

        if (admin || PermissionUtils.can(voicePermissionMask, permissions) || channel.isDM())
            setRecordButtonVisibility(View.VISIBLE);
        else
            setRecordButtonVisibility(View.GONE);
    }

    public void setRecordButtonVisibility (int visibility){
        Utils.mainThread.post(() -> recordButton.setVisibility(visibility));
    }

    @Override
    public void stop(Context context) {
        if (isRecording) {
            onRecordStop(false, 0L);
        }
        try {
            mediaRecorder.release();
        } catch (RuntimeException ignored) {}
        locked = false;
        detachFromParent(waveFormView);
        detachFromParent(recordButton);
        inputContainer = null;
        patcher.unpatchAll();
        commands.unregisterAll();
    }
}

