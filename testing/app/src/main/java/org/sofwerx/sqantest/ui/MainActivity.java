package org.sofwerx.sqantest.ui;

import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import org.sofwerx.sqantest.R;
import org.sofwerx.sqantest.ipc.IpcBroadcastTransceiver;
import org.sofwerx.sqantest.packet.AbstractPacket;
import org.sofwerx.sqantest.packet.PacketHeader;
import org.sofwerx.sqantest.packet.RawBytesPacket;
import org.sofwerx.sqantest.util.PackageUtil;

import java.io.UnsupportedEncodingException;

public class MainActivity extends AppCompatActivity implements IpcBroadcastTransceiver.IpcBroadcastListener {
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;

            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View buttonTest = findViewById(R.id.mainButtonTest);
        if (buttonTest != null) {
            buttonTest.setOnClickListener(view -> {
                RawBytesPacket packet = new RawBytesPacket(new PacketHeader());
                try {
                    packet.setData("Mr. Watson — Come here — I want to see you".getBytes("UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                IpcBroadcastTransceiver.broadcast(this,packet.toByteArray());
            });
        }
        if (!PackageUtil.doesSqAnExist(this)) {
            Toast.makeText(this,"SqAN is not installed, so it cannot be tested.",Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        IpcBroadcastTransceiver.register(this,this);
    }

    @Override
    public void onPause() {
        IpcBroadcastTransceiver.unregister(this);
        super.onPause();
    }

    @Override
    public void onPacketReceived(byte[] packet) {
        AbstractPacket abstractPacket = AbstractPacket.newFromBytes(packet);
        if (abstractPacket != null) {
            byte[] data = null;
            if (abstractPacket instanceof RawBytesPacket) {
                RawBytesPacket rawPkt = (RawBytesPacket)abstractPacket;
                data = rawPkt.getData();
            }
            if (data == null)
                Toast.makeText(this,packet.length+"b packet received over SqAn",Toast.LENGTH_LONG).show();
            else {
                try {
                    Toast.makeText(this,"Received: "+new String(data,"UTF-8"),Toast.LENGTH_LONG).show();
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
