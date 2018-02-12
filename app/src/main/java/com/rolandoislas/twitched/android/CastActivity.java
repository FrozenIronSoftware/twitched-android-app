package com.rolandoislas.twitched.android;

import android.content.Intent;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import com.goebl.david.Response;
import com.goebl.david.Webb;
import com.goebl.david.WebbException;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.rolandoislas.twitched.android.MainActivity.PREF_MAIN;
import static com.rolandoislas.twitched.android.MainActivity.ROKU_IP;

public class CastActivity extends AppCompatActivity {

    private static final String APP_ID = "206723";
    private Logger logger;
    private Webb webb;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cast);
        // Fields
        logger = Logger.getLogger("Twitched Cast");
        webb = Webb.create();
        handler = new Handler(getMainLooper());
        // Parse launch data
        Intent launcher = getIntent();
        if (launcher == null) {
            logger.info("Launched without an intent");
            exit();
            return;
        }
        String extraText = launcher.getStringExtra(Intent.EXTRA_TEXT);
        if (extraText == null) {
            logger.info("No extra text supplied");
            exit();
            return;
        }
        logger.info(String.format("Data: %s", extraText));
        Pattern twitchChannelUrl = Pattern.compile(".*https?://.*twitch.tv/([^?#&]+).*");
        Pattern twitchVideoUrl = Pattern.compile(".*https?://.*twitch.tv/(?:[^?#&/]+)/v/([^?#&]+)(?:.*t=(\\d+))?.*");
        Matcher channelMatcher = twitchChannelUrl.matcher(extraText);
        Matcher videoMatcher = twitchVideoUrl.matcher(extraText);
        if (!channelMatcher.matches() && !videoMatcher.matches()) {
            logger.info(String.format("Extra text does not contain a Twitch URL: %s", extraText));
            exit();
            return;
        }
        if (videoMatcher.matches()) {
            String id = videoMatcher.group(1);
            String time = videoMatcher.group(2);
            cast(null, id, time);
        }
        else if (channelMatcher.matches()) {
            String userName = channelMatcher.group(1);
            cast(userName, null, null);
        }
    }

    /**
     * Cast to the roku
     * @param userName twitch channel name
     * @param videoId video id
     * @param time
     */
    private void cast(@Nullable final String userName, @Nullable final String videoId, @Nullable final String time) {
        final String ip = getSharedPreferences(PREF_MAIN, MODE_PRIVATE).getString(ROKU_IP, "");
        if (ip.isEmpty()) {
            exit(R.string.message_no_ip_set);
            return;
        }
        Thread castThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String contentId;
                String mediaType;
                if (userName != null) {
                    contentId = String.format("twitch_stream_%s", userName);
                    mediaType = "live";
                }
                else if (videoId != null) {
                    contentId = String.format("twitch_video_%s", videoId);
                    mediaType = "special";
                }
                else
                    throw new RuntimeException("Invalid arguments");
                // Post to Roku
                try {
                    Response<Void> response = webb.post(String.format(
                            "http://%s:8060/launch/%s?contentId=%s&mediaType=%s&time=%s",
                            ip,
                            APP_ID,
                            contentId,
                            mediaType,
                            time == null ? "0" : time
                    ))
                            .body("")
                            .retry(1, false)
                            .ensureSuccess()
                            .asVoid();
                }
                catch (WebbException e) {
                    e.printStackTrace();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            exit(R.string.message_cast_fail);
                        }
                    });
                    return;
                }
                // Cast was successful
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        MainActivity.showMessage(getBaseContext(), R.string.message_cast_success, true);
                        finish();
                    }
                });
            }
        });
        castThread.setDaemon(true);
        castThread.setName("Cast");
        castThread.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

    }

    /**
     * Start the main activity
     */
    private void exit(int reasonMessageId) {
        Intent main = new Intent(getBaseContext(), MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (reasonMessageId > -1)
            MainActivity.showMessage(getBaseContext(), reasonMessageId, true);
        startActivity(main);
        finish();
    }

    /**
     * Start the main activity with no specified message
     */
    private void exit() {
        exit(-1);
    }
}
