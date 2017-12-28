package com.rolandoislas.twitched.android;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.goebl.david.Response;
import com.goebl.david.Webb;
import com.goebl.david.WebbException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    static final String PREF_MAIN = "preferences_main";
    static final String ROKU_IP = "roku_ip";
    public static final String MSG_ERR = MainActivity.class.getSimpleName() + "msg.error";
    private Logger logger;
    private Webb webb;
    private Handler handler;
    private List<String> rokus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fields
        logger = Logger.getLogger("Twitched");
        webb = Webb.create();
        handler = new Handler(getMainLooper());
        rokus = new ArrayList<>();
        // Set view
        setContentView(R.layout.activity_main);
        // Handle list item click
        ListView rokuList = (ListView) findViewById(R.id.rokuList);
        rokuList.setEmptyView(findViewById(R.id.searchIndicator));
        rokuList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!(view instanceof TextView))
                    return;
                TextView textView = (TextView) view;
                String[] dashSplit = textView.getText().toString().split("-");
                String ip = dashSplit[dashSplit.length - 1].trim();
                TextView ipField = (TextView) findViewById(R.id.ipField);
                ipField.setText(ip);
                saveIp(ip);
                showMessage(R.string.message_ip_saved);
            }
        });
        // Handle ip field submit
        TextView ipField = (TextView) findViewById(R.id.ipField);
        ipField.setText(getIp());
        ipField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView ipField, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    if (ipField.getText().toString().isEmpty())
                        return false;
                    saveIp(ipField.getText().toString());
                    showMessage(R.string.message_ip_saved);
                    return true;
                }
                return false;
            }
        });
        // Start search
        searchForRokus();
    }

    /**
     * Get the saved roku up
     * @return ip
     */
    private String getIp() {
        return getSharedPreferences(PREF_MAIN, MODE_PRIVATE).getString(ROKU_IP, "");
    }

    /**
     * Show a toast message
     * @param msg id
     */
    private void showMessage(int msg) {
        showMessage(msg, false);
    }

    /**
     * Show a toast message
     * @param msg id
     * @param showLong show a long duration toast
     */
    private void showMessage(int msg, boolean showLong) {
        showMessage(getBaseContext(), msg, showLong);
    }

    /**
     * Show a toast message
     * @param context message context
     * @param msg message id
     * @param showLong long duration
     */
    static void showMessage(Context context, int msg, boolean showLong) {
        Toast toast = Toast.makeText(context, msg, showLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 100);
        toast.show();
    }

    /**
     * Save the IP to local storage
     * @param ip ip value to save
     */
    private void saveIp(String ip) {
        getSharedPreferences(PREF_MAIN, MODE_PRIVATE).edit().putString(ROKU_IP, ip).apply();
    }

    /**
     * Start a background search for Rokus on the network
     */
    private void searchForRokus() {
        Thread ssdpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                searchSsdp();
            }
        });
        ssdpThread.setName("SSDP");
        ssdpThread.setDaemon(true);
        logger.info("Starting search thread");
        ssdpThread.start();
    }

    /**
     * Search for Rokus via SSDP
     */
    private void searchSsdp() {
        byte[] msearchMessage =
                "M-SEARCH * HTTP/1.1\nHost: 239.255.255.250:1900\nMan: \"ssdp:discover\"\nST: roku:ecp\n"
                        .getBytes();
        try {
            InetAddress sendTo = InetAddress.getByName("239.255.255.250");
            DatagramPacket msearchPacket = new DatagramPacket(msearchMessage, msearchMessage.length, sendTo, 1900);
            DatagramSocket msearchSocket = new DatagramSocket();
            byte[] responseBuffer = new byte[2048];
            msearchSocket.send(msearchPacket);
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            for (int reponseNumber = 0; reponseNumber < 20; reponseNumber++) {
                msearchSocket.receive(responsePacket);
                String response = new String(responsePacket.getData());
                // Parse response
                if (!response.startsWith("HTTP/1.1 200 OK"))
                    continue;
                String[] locationSplit = response.split("LOCATION:");
                if (locationSplit.length < 2)
                    continue;
                String[] newLineSplit = locationSplit[1].split("(?:\\r*)\\n");
                String location = newLineSplit[0].trim();
                Pattern ipv4Regex = Pattern.compile("http(?:s?)://((?:\\d{1,3}\\.){3}\\d{3}).*");
                Matcher matcher = ipv4Regex.matcher(location);
                if (!matcher.matches())
                    continue;
                String ip = matcher.group(1);
                addRokuToSearchList(ip);
            }
            msearchSocket.close();
            logger.info("Search finished");
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Add a roku to the search list
     * @param ip roku ip
     */
    private void addRokuToSearchList(final String ip) {
        // Query device for its name
        Response<String> response;
        try {
            response = webb
                .get(String.format("http://%s:8060/query/device-info", ip))
                .retry(1, false)
                .ensureSuccess()
                .asString();
        }
        catch (WebbException e) {
            e.printStackTrace();
            return;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder;
        Document info;
        try {
            documentBuilder = factory.newDocumentBuilder();
            info = documentBuilder.parse(new InputSource(new StringReader(response.getBody())));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
            return;
        }
        NodeList deviceInfoNodes = info.getElementsByTagName("device-info");
        if (deviceInfoNodes.getLength() < 1)
            return;
        Node deviceInfo = deviceInfoNodes.item(0);
        String vendor = "";
        String userDeviceName = "";
        for (int nodeIndex = 0; nodeIndex < deviceInfo.getChildNodes().getLength(); nodeIndex++) {
            Node node = deviceInfo.getChildNodes().item(nodeIndex);
            if (node.getNodeName().equals("vendor-name"))
                vendor = node.getTextContent();
            else if (node.getNodeName().equals("user-device-name"))
                userDeviceName = node.getTextContent();
        }
        if (!vendor.equalsIgnoreCase("ROKU"))
            return;
        // Add to list
        final String finalUserDeviceName = userDeviceName;
        handler.post(new Runnable() {
            @Override
            public void run() {
                ListView rokuList = (ListView) findViewById(R.id.rokuList);
                rokus.add(finalUserDeviceName + " - " + ip);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(getBaseContext(), android.R.layout.simple_list_item_1,
                        rokus);
                rokuList.setAdapter(adapter);
            }
        });
    }
}
