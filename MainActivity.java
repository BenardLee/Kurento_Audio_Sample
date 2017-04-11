package com.example.alnova2.kurentohelloex;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.tavendo.autobahn.WebSocket;
import de.tavendo.autobahn.WebSocketConnection;

public class MainActivity extends AppCompatActivity {
    private final String TAG="MainActivity";

    //UI Components
    private ToggleButton mConnectButton;
    private Button mStartButton;
    private Button mStopButton;
    private Spinner mCodecSpinner;
    private TextView mAudioBitRateText;
    private Switch mAudioEchoCancelationSwitch;
    private Switch mAudioAutoGainControlSwitch;
    private Switch mAudioHighPassFilterSwitch;
    private Switch mAudioNoiseSupressionSwitch;
    private Switch mAudioLevelControlSwitch;
    private Switch mAudioBitRateSwitch;
    private TextView mAppLogTextView;
    private LinkedList<IceCandidate> queuedRemoteCandidates;
    private LinkedList<IceCandidate> queuedLocalCandidates;

    //Audio Configurations
    private WebSocketConnection mConnection=new WebSocketConnection();
    private WebSocketObserver mWSObserver=new WebSocketObserver();
    private final String wsuri="ws://211.189.163.192:8080/call";
    private String mWSStatus="NOT_CONNECTED";
    private final SDPObserver mSDPObserver = new SDPObserver();
    private final PCObserver mPCObserver = new PCObserver();
    private String preferredVideoCodec;

    //WebRTC Related Components
    private AppRTCAudioManager mLocalAudioManager;
    List<PeerConnection.IceServer> mIceServers=new ArrayList<PeerConnection.IceServer>();

    private MediaStream mLocalMediaStream;
    private MediaConstraints mLocalMediaConstrants;
    private MediaConstraints mLocalAudioConstraints;
    private MediaConstraints mPeerConnectionConstraints;
    private PeerConnectionFactory mPeerConnectionFactory;
    private PeerConnection mPeerConnection;

    private AudioSource mLocalAudioSource;
    private AudioTrack mRemoteAudioTrack;
    private AudioTrack mLocalAudioTrack;


    private List<PeerConnection.IceServer> iceServers=new ArrayList<PeerConnection.IceServer>();

    //From AppRetDemo
    private static final String AUDIO_CODEC_ISAC = "ISAC";
    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";

    private boolean mIsInitiator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //1. Setup UI Components
        mConnectButton=(ToggleButton)findViewById(R.id.toggleButton);
        mConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG,"Button Status:"+mConnectButton.isChecked());
                //Disconnect WebSocket
                if(mConnectButton.isChecked()){
                    connectWS(wsuri);
                } else {
                    if(mWSStatus.equals("CONNECTED")) {
                        disconnectWS();
                        mConnectButton.setChecked(false);
                    }
                }
            }
        });
        mStartButton =(Button)findViewById(R.id.startButton);
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try{
                    //Create Offer
                    if (mPeerConnection != null) {
                        Log.d(TAG, "PC Create OFFER");
                        mIsInitiator=true;
                        mPeerConnection.createOffer(mSDPObserver, mLocalMediaConstrants);
                    }
                } catch (Exception e){
                    Log.d(TAG,"Send Msg Error");
                }
            }
        });
        mStopButton=(Button)findViewById(R.id.stoptButton);
        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mWSStatus.equals("CONNECTED")) {
                    JSONObject json=new JSONObject();
                    try {
                        json.put("id","stop");
                        mConnection.sendTextMessage(json.toString());
                        disconnectWS();
                        mConnectButton.setChecked(false);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        mCodecSpinner=(Spinner)findViewById(R.id.codecSpinner);
        ArrayAdapter codecAdapter=ArrayAdapter.createFromResource(this,R.array.audioCodecs,android.R.layout.simple_spinner_item);
        mCodecSpinner.setAdapter(codecAdapter);
        mAudioEchoCancelationSwitch = (Switch)findViewById(R.id.audioEchoCancelationSwitch);
        mAudioAutoGainControlSwitch = (Switch)findViewById(R.id.audioAutoGainControlSwitch);
        mAudioHighPassFilterSwitch = (Switch)findViewById(R.id.audioHighPassFilterSwitch);
        mAudioNoiseSupressionSwitch = (Switch)findViewById(R.id.audioNoiseSupressionSwitch);
        mAudioLevelControlSwitch = (Switch)findViewById(R.id.audioLevelControlSwitch);
        mAudioBitRateSwitch = (Switch)findViewById(R.id.audioBitRateSwitch);
        mAudioBitRateText=(TextView)findViewById(R.id.audioBitRateText);
        mAppLogTextView=(TextView)findViewById(R.id.AppLog);

        Log.d(TAG, "Starting the audio manager...");
        mLocalAudioManager = AppRTCAudioManager.create(getApplicationContext());
        mLocalAudioManager.start(new AppRTCAudioManager.AudioManagerEvents() {
            // This method will be called each time the number of available audio
            // devices has changed.
            @Override
            public void onAudioDeviceChanged(
                    AppRTCAudioManager.AudioDevice audioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
                onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
            }
        });


        //Create MediaConstraints
        mLocalAudioConstraints = new MediaConstraints();
        //set audio processing
        setAudioProcessing();

        //Set MediaConstraints - Just receive audio
        mLocalMediaConstrants = new MediaConstraints();
        mLocalMediaConstrants.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mLocalMediaConstrants.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        //Create PeerConnection
        Log.d(TAG, "Create PeerConnectionFactory...");
        if (!PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true)) {
            Log.d(TAG, "Failed to initializeAndroidGlobals");
            return;
        }
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.networkIgnoreMask = 0;
        mPeerConnectionFactory=new PeerConnectionFactory(options);

        Log.d(TAG,"Create Local MediaStream");
        mLocalMediaStream =mPeerConnectionFactory.createLocalMediaStream("ARDAMS");
        //Create AudioTrack
        mLocalAudioSource =mPeerConnectionFactory.createAudioSource(mLocalAudioConstraints);
        mLocalAudioTrack=mPeerConnectionFactory.createAudioTrack("ARDAMSa0", mLocalAudioSource);
        mLocalAudioTrack.setEnabled(true);
        //Add Local AudioTrack to MediaStream
        mLocalMediaStream.addTrack(mLocalAudioTrack);

        //Create PeerConnection
        Log.d(TAG, "Create PeerConnection...");
        queuedRemoteCandidates = new LinkedList<IceCandidate>();
        queuedLocalCandidates = new LinkedList<IceCandidate>();
        //iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302","",""));
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        //Create PeerConnection Constraints
        mPeerConnectionConstraints = new MediaConstraints();
        mPeerConnectionConstraints.optional.add(new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        mPeerConnection= mPeerConnectionFactory.createPeerConnection(rtcConfig,mPeerConnectionConstraints,mPCObserver);
        //Add Local MediaStream to PeerConnection
        mPeerConnection.addStream(mLocalMediaStream);
        mAppLogTextView.append("PeerConnnection Created.\n");
        mIsInitiator = false;
    }

    private void setAudioProcessing(){
        String AudioEchoCancelationSwitch="false";
        String AudioAutoGainControlSwitch="false";
        String AudioHighPassFilterSwitch="false";
        String AudioNoiseSupressionSwitch="false";
        String AudioLevelControlSwitch="false";

        if(mAudioEchoCancelationSwitch.isChecked()) AudioEchoCancelationSwitch="true";
        if(mAudioAutoGainControlSwitch.isChecked()) AudioAutoGainControlSwitch="true";
        if(mAudioHighPassFilterSwitch.isChecked()) AudioHighPassFilterSwitch="true";
        if(mAudioNoiseSupressionSwitch.isChecked()) AudioNoiseSupressionSwitch="true";
        if(mAudioLevelControlSwitch.isChecked()) AudioLevelControlSwitch="true";

        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, AudioEchoCancelationSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, AudioAutoGainControlSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, AudioNoiseSupressionSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, AudioHighPassFilterSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, AudioLevelControlSwitch));

        if(mAudioBitRateSwitch.isChecked()){
            Log.d(TAG,"AudioBitRateSwitch is on..Value:"+mAudioBitRateText.toString());
        }

    }

    private boolean connectWS(String uri){
        try{
            mConnection.connect(new URI(uri),mWSObserver);
        } catch (Exception e){
            Log.d(TAG,"Exception:connectWS:Conneceting To WS Error:"+e.getMessage());
            return false;
        }
        return true;
    }

    private void disconnectWS(){
        Log.d(TAG, "disconnect Websocket");
        mConnection.disconnect();
        mWSStatus="NOT_CONNECTED";
    }

    private boolean sendWSMsg(JSONObject sendMsgJson){
        if(mWSStatus.equals("CONNECTED")){
            Log.d(TAG,"send Websocket data:"+sendMsgJson.toString());
            mConnection.sendTextMessage(sendMsgJson.toString());
            return true;
        } else return false;
    }

    private class WebSocketObserver implements WebSocket.WebSocketConnectionObserver {
        @Override
        public void onOpen() {
            Log.d(TAG, "WebSocket connection opened to:");
            mWSStatus="CONNECTED";
            mConnectButton.setChecked(true);
        }

        @Override
        public void onClose(WebSocket.WebSocketConnectionObserver.WebSocketCloseNotification code, String reason) {
            Log.d(TAG, "WebSocket connection closed. Code: " + code + ". Reason: " + reason );
        }

        @Override
        public void onTextMessage(String payload) {
            Log.d(TAG, "WSS->C: " + payload);
            try {
                JSONObject json = new JSONObject(payload);
                String msgId=json.getString("id");
                Log.d(TAG,"WSS->C:msgid:"+msgId);
                //webRtcPeer.processAnswer(message.sdpAnswer)
                if(msgId.equals("response")){
                    mAppLogTextView.append("Previous Member Cnt:"+json.get("membercnt")+"\n");
                    //Signaling Server sends SDP answer message..
                    mIsInitiator=false;
                    SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.fromCanonicalForm("answer"), json.getString("sdpAnswer"));
                    mPeerConnection.setRemoteDescription(mSDPObserver,sdpAnswer);

                } else if(msgId.equals("iceCandidate")){
                    processRemoteCandidate(json.getJSONObject("candidate"));
                } else if(msgId.equals("memberleave")){
                    mAppLogTextView.append(json.get("name")+" leaves\n");
                } else if(msgId.equals("memberjoin")){
                    mAppLogTextView.append(json.get("name")+" joins\n");
                } else if(msgId.equals("stopCommunication")){

                } else {
                    Log.d(TAG,"Unrecognized message :"+payload);
                }
            } catch (Exception e){
                Log.d(TAG,"WebSocket Data Parsing Error:"+e.getMessage());
            }
        }

        @Override
        public void onRawTextMessage(byte[] payload) {}

        @Override
        public void onBinaryMessage(byte[] payload) {}
    }

    //A Class for observing SDP Change
    private class SDPObserver implements SdpObserver {
        private SessionDescription localSdp;
        @Override
        public void onCreateSuccess(SessionDescription origSdp) {
            Log.d(TAG,"SessionDescriptor create success.");
            //From AppRTCDemo
            if (localSdp != null) {
                Log.d(TAG,"Multiple SDP create.");
                return;
            }
            String sdpDescription = origSdp.description;
            if (false) {
                sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
            }

            if (true) {
                sdpDescription = preferCodec(sdpDescription, preferredVideoCodec, false);
            }
            final SessionDescription sdp = new SessionDescription(origSdp.type, sdpDescription);
            localSdp = sdp;
            mPeerConnection.setLocalDescription(mSDPObserver, sdp);
        }

        @Override
        public void onSetSuccess() {
            if(mPeerConnection==null) return;
            if(mIsInitiator){
                if(mPeerConnection.getRemoteDescription()==null) {
                    Log.d(TAG, "Send LocalDescriptor to MediaServer");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Log.d(TAG, "Set local SDP from " + localSdp.type);
                            try{
                                JSONObject json=new JSONObject();
                                json.put("id","client");
                                json.put("name","galaxy");
                                json.put("sdpOffer",localSdp.description);
                                Log.d(TAG, "Send sdpOffer to signaling Server :"+json.toString());
                                mConnection.sendTextMessage(json.toString());
                            } catch (Exception e){
                                Log.d(TAG,"Exception occured when send sdpoffer:"+e.getMessage());
                            }
                        }
                    });
                } else {
                    Log.d(TAG, "We just set remotedescriptor. drain remote/local ICE Candidate");
                    drainRemoteCandidates();
                }
            } else {
                if(mPeerConnection.getLocalDescription()!=null){
                    Log.d(TAG,"Local SDP set successfully");
                    drainRemoteCandidates();
                } else {
                    Log.d(TAG, "Remote SDP set successfully");
                }
            }
        }
        @Override
        public void onCreateFailure(String s) {

        }
        @Override
        public void onSetFailure(String s) {

        }
    }

    //A Class for observing PeerConnection CHange
    private class PCObserver implements PeerConnection.Observer{
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            //This is called when local IceCandidate is occured.
            Log.d(TAG,"Local onIceCandidate event");
            queuedLocalCandidates.add(iceCandidate);

            if(mWSStatus.equals("CONNECTED")){
                Log.d(TAG,"onIceCandidate:Send icecandidate to signaling server");
                try {
                    JSONObject json = new JSONObject();
                    json.put("id", "onIceCandidate");
                    JSONObject candidateJson=new JSONObject();
                    candidateJson.put("candidate",iceCandidate.sdp);
                    candidateJson.put("sdpMid",iceCandidate.sdpMid);
                    candidateJson.put("sdpMLineIndex",iceCandidate.sdpMLineIndex);
                    json.put("candidate", candidateJson);
                    Log.d(TAG,"onIceCandidate:Send:"+json.toString());
                    mConnection.sendTextMessage(json.toString());
                } catch (Exception e){
                    Log.d(TAG,"onIceCandidate:Error when sending icecandidate. Exception:"+e.getMessage());
                }
            } else {
                Log.d(TAG,"onIceCndadate:Cannot send icecandidate to signaling server because websocket is not connected.");
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG,"onAddStream event");
            if (mPeerConnection == null) {
                return;
            }
            if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                Log.d(TAG,"Weird-looking stream: " + mediaStream);
                return;
            }
            if (mediaStream.audioTracks.size() == 1) {
                Log.d(TAG,"audioTracks set");
                mRemoteAudioTrack=mediaStream.audioTracks.get(0);
                mRemoteAudioTrack.setEnabled(true);
            }
        }
        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            mediaStream.audioTracks.get(0).dispose();
        }
        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }
        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }
    }


    //This is from AppRTCDemo
    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }


    private String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String codecRtpMap = null;
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        String mediaDescription = "m=video ";
        if (isAudio) {
            mediaDescription = "m=audio ";
        }
        for (int i = 0; (i < lines.length) && (mLineIndex == -1 || codecRtpMap == null); i++) {
            if (lines[i].startsWith(mediaDescription)) {
                mLineIndex = i;
                continue;
            }
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
            }
        }
        if (mLineIndex == -1) {
            Log.w(TAG, "No " + mediaDescription + " line, so can't prefer " + codec);
            return sdpDescription;
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec);
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at " + lines[mLineIndex]);
        String[] origMLineParts = lines[mLineIndex].split(" ");
        if (origMLineParts.length > 3) {
            StringBuilder newMLine = new StringBuilder();
            int origPartIndex = 0;
            // Format is: m=<media> <port> <proto> <fmt> ...
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(codecRtpMap);
            for (; origPartIndex < origMLineParts.length; origPartIndex++) {
                if (!origMLineParts[origPartIndex].equals(codecRtpMap)) {
                    newMLine.append(" ").append(origMLineParts[origPartIndex]);
                }
            }
            lines[mLineIndex] = newMLine.toString();
            Log.d(TAG, "Change media description: " + lines[mLineIndex]);
        } else {
            Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex]);
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines) {
            newSdpDescription.append(line).append("\r\n");
        }
        return newSdpDescription.toString();
    }

    private void processRemoteCandidate(JSONObject candidateIn) {
        Log.d(TAG,"Add Remote IceCandidate:"+candidateIn.toString());
        try {
            IceCandidate candidate = new IceCandidate(
                    (String) candidateIn.get("sdpMid"),
                    candidateIn.getInt("sdpMLineIndex"),
                    (String) candidateIn.get("candidate")
            );
            Log.d(TAG,"Candidate ToString:"+candidate.toString());
            //mPeerConnection.addIceCandidate(candidate);
            if(queuedRemoteCandidates==null) {
                mPeerConnection.addIceCandidate(candidate);
            } else queuedRemoteCandidates.add(candidate);
        } catch (Exception e){
            Log.d(TAG,"processRemoteCandidate Ex:"+e.toString());
        }

    }
    private void onAudioManagerDevicesChanged(
            final AppRTCAudioManager.AudioDevice device, final Set<AppRTCAudioManager.AudioDevice> availableDevices) {
        Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", " + "selected: " + device);
    }
    private void drainRemoteCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                mPeerConnection.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
        }
    }
    private void drainLocalCandidates() {
        if (queuedLocalCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedLocalCandidates) {
                if(mWSStatus.equals("CONNECTED")){
                    Log.d(TAG,"onIceCandidate:Send icecandidate to signaling server");
                    try {
                        JSONObject json = new JSONObject();
                        json.put("id", "onIceCandidate");
                        JSONObject candidateJson=new JSONObject();
                        candidateJson.put("candidate",candidate.sdp);
                        candidateJson.put("sdpMid",candidate.sdpMid);
                        candidateJson.put("sdpMLineIndex",candidate.sdpMLineIndex);
                        json.put("candidate", candidateJson);
                        Log.d(TAG,"onIceCandidate:Send:"+json.toString());
                        mConnection.sendTextMessage(json.toString());
                    } catch (Exception e){
                        Log.d(TAG,"onIceCandidate:Error when sending icecandidate. Exception:"+e.getMessage());
                    }
                } else {
                    Log.d(TAG,"onIceCndadate:Cannot send icecandidate to signaling server because websocket is not connected.");
                }
            }
            queuedLocalCandidates = null;
        }
    }
}
