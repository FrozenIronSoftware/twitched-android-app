package com.rolandoislas.twitched.android;

import android.content.Intent;
import android.os.Handler;
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

    private static final String APP_ID = "206723_e381"; // TODO update the unpublished id to the published id
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
        Pattern twitchChannelUrl = Pattern.compile(".*https?://.*twitch.tv/(.*)(?:\\?.*)+?");
        Matcher matcher = twitchChannelUrl.matcher(extraText);
        if (!matcher.matches()) {
            logger.info(String.format("Extra text does not contain a Twitch URL: %s", extraText));
            exit();
            return;
        }
        String userName = matcher.group(1);
        cast(userName);
    }

    /**
     * Cast to the roku
     * @param userName twitch channel name
     */
    private void cast(final String userName) {
        final String ip = getSharedPreferences(PREF_MAIN, MODE_PRIVATE).getString(ROKU_IP, "");
        if (ip.isEmpty()) {
            exit(R.string.message_no_ip_set);
            return;
        }
        Thread castThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Post to Roku
                try {
                    Response<Void> response = webb.post(String.format(
                            "http://%s:8060/launch/%s?contentId=twitch_stream&mediaType=live&twitch_user_name=%s",
                            ip,
                            APP_ID,
                            userName))
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
