package yandex.cloud.speechkit.examples.service;

import com.google.protobuf.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import yandex.cloud.api.ai.stt.v3.Stt;
import yandex.cloud.speechkit.examples.SttV3Client;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for speech recognition.
 * Accepts binary WebSocket messages with audio chunks and returns text recognition results.
 */
@Component
public class WebSocketSpeechController extends AbstractWebSocketHandler {

    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();
    private final String host;
    private final int port;
    private final String apiKey;

    public WebSocketSpeechController(
            @Value("${speechkit.host:stt.api.cloud.yandex.net}") String host,
            @Value("${speechkit.port:443}") int port,
            @Value("${speechkit.apiKey:#{environment.API_KEY}}") String apiKey) {
        this.host = host;
        this.port = port;
        this.apiKey = apiKey;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.put(sessionId, new SessionData(session));
        System.out.println("WebSocket connection established: " + sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        SessionData sessionData = sessions.get(sessionId);

        if (sessionData == null) {
            session.close(CloseStatus.SERVER_ERROR.withReason("Session not found"));
            return;
        }

        // Parse configuration message from client
        // Expected format: {"sampleRate": 16000, "language": "ru-RU"}
        String payload = message.getPayload();
        Map<String, Object> config = parseConfig(payload);

        if (config.containsKey("sampleRate")) {
            int sampleRate = (int) config.get("sampleRate");
            sessionData.setSampleRate(sampleRate);
        }

        if (config.containsKey("language")) {
            String language = (String) config.get("language");
            sessionData.setLanguage(language);
        }

        // Initialize SpeechKit client and start streaming
        if (!sessionData.isInitialized()) {
            SttV3Client client = new SttV3Client(host, port, apiKey);
            sessionData.setClient(client);
            sessionData.setInitialized(true);

            // Start streaming session and send initial request
            sessionData.startStreaming(session);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        String sessionId = session.getId();
        SessionData sessionData = sessions.get(sessionId);

        if (sessionData == null || !sessionData.isInitialized()) {
            session.close(CloseStatus.SERVER_ERROR.withReason("Session not initialized"));
            return;
        }

        // Process audio chunk
        byte[] audioData = message.getPayload().array();
        sessionData.processAudioChunk(audioData);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        SessionData sessionData = sessions.remove(sessionId);

        if (sessionData != null) {
            sessionData.completeStreaming();
        }

        System.out.println("WebSocket connection closed: " + sessionId + ", status: " + status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.err.println("WebSocket transport error: " + exception.getMessage());
        try {
            session.close(CloseStatus.SERVER_ERROR.withReason("Transport error"));
        } catch (IOException e) {
            // Ignore
        }
    }

    private Map<String, Object> parseConfig(String payload) {
        // Simple JSON parsing, in a real project, use a proper JSON library
        Map<String, Object> result = new ConcurrentHashMap<>();

        // This is a very simplistic JSON parser for demo purposes
        // In a real application, use a proper JSON library like Jackson or Gson
        if (payload.contains("sampleRate")) {
            String sampleRateStr = payload.replaceAll(".*\"sampleRate\"\\s*:\\s*(\\d+).*", "$1");
            try {
                result.put("sampleRate", Integer.parseInt(sampleRateStr));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        if (payload.contains("language")) {
            String language = payload.replaceAll(".*\"language\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            result.put("language", language);
        }

        return result;
    }

    /**
     * Helper class to store WebSocket session data and manage STT streaming
     */
    private static class SessionData {
        private final WebSocketSession webSocketSession;
        private SttV3Client client;
        private int sampleRate = 16000; // Default sample rate
        private String language = "ru-RU"; // Default language
        private boolean initialized = false;
        private StreamHandler streamHandler;

        public SessionData(WebSocketSession webSocketSession) {
            this.webSocketSession = webSocketSession;
        }

        public void setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public void setClient(SttV3Client client) {
            this.client = client;
        }

        public boolean isInitialized() {
            return initialized;
        }

        public void setInitialized(boolean initialized) {
            this.initialized = initialized;
        }

        public void startStreaming(WebSocketSession session) {
            this.streamHandler = new StreamHandler(session, language, sampleRate);
            // Logic for starting streaming recognition
        }

        public void processAudioChunk(byte[] audioData) {
            if (streamHandler != null) {
                streamHandler.sendAudioChunk(audioData);
            }
        }

        public void completeStreaming() {
            if (streamHandler != null) {
                streamHandler.complete();
                streamHandler = null;
            }
        }
    }

    /**
     * Handler for streaming recognition
     */
    private static class StreamHandler implements io.grpc.stub.StreamObserver<Stt.StreamingResponse> {
        private final WebSocketSession session;
        private io.grpc.stub.StreamObserver<Stt.StreamingRequest> requestObserver;
        private final String language;
        private final int sampleRate;
        private StringBuilder result = new StringBuilder();

        public StreamHandler(WebSocketSession session, String language, int sampleRate) {
            this.session = session;
            this.language = language;
            this.sampleRate = sampleRate;

            // Create SpeechKit client
            SttV3Client client = new SttV3Client(
                    "stt.api.cloud.yandex.net",
                    443,
                    System.getenv("API_KEY"));

            // Start streaming
            this.requestObserver = client.startStreaming(this);

            // Send initial request
            Stt.StreamingRequest initialRequest = SttV3Client.createInitialRequest(sampleRate, language);
            requestObserver.onNext(initialRequest);
        }

        public void sendAudioChunk(byte[] audioData) {
            if (requestObserver != null) {
                try {
                    Stt.StreamingRequest request = SttV3Client.createAudioChunkRequest(audioData);
                    requestObserver.onNext(request);
                } catch (Exception e) {
                    System.err.println("Error sending audio chunk: " + e.getMessage());
                }
            }
        }

        public void complete() {
            if (requestObserver != null) {
                try {
                    requestObserver.onCompleted();
                } catch (Exception e) {
                    System.err.println("Error completing request: " + e.getMessage());
                } finally {
                    requestObserver = null;
                }
            }
        }

        @Override
        public void onNext(Stt.StreamingResponse response) {
            try {
                if (response.hasFinal()) {
                    // Process final recognition result
                    String text = response.getFinal().getAlternativesList().stream()
                            .map(Stt.Alternative::getText)
                            .findFirst()
                            .orElse("");

                    if (!text.isEmpty()) {
                        result.append(text).append(" ");

                        // Send the result to the WebSocket client
                        session.sendMessage(new TextMessage(text));
                    }
                } else if (response.hasPartial()) {
                    // Process partial recognition result
                    String text = response.getPartial().getAlternativesList().stream()
                            .map(Stt.Alternative::getText)
                            .findFirst()
                            .orElse("");

                    if (!text.isEmpty()) {
                        // Send the partial result to the WebSocket client
                        session.sendMessage(new TextMessage("{ \"partial\": \"" + text + "\" }"));
                    }
                }
            } catch (IOException e) {
                System.err.println("Error sending WebSocket message: " + e.getMessage());
            }
        }

        @Override
        public void onError(Throwable t) {
            System.err.println("Streaming error: " + t.getMessage());
            try {
                session.sendMessage(new TextMessage("{ \"error\": \"" + t.getMessage() + "\" }"));
                session.close(CloseStatus.SERVER_ERROR.withReason("STT error"));
            } catch (IOException e) {
                // Ignore
            }
        }

        @Override
        public void onCompleted() {
            try {
                // Send the final complete result
                session.sendMessage(new TextMessage("{ \"result\": \"" + result.toString().trim() + "\" }"));
            } catch (IOException e) {
                System.err.println("Error sending final result: " + e.getMessage());
            }
        }

        private Stt.StreamingRequest createInitialRequest(int sampleRate, String language) {
            return Stt.StreamingRequest.newBuilder()
                    .setSessionOptions(Stt.StreamingOptions.newBuilder()
                            .setRecognitionModel(Stt.RecognitionModelOptions.newBuilder()
                                    .setLanguageRestriction(Stt.LanguageRestrictionOptions.newBuilder()
                                            .addLanguageCode(language)
                                            .setRestrictionType(Stt.LanguageRestrictionOptions.LanguageRestrictionType.WHITELIST)
                                            .build())
                                    .setAudioFormat(Stt.AudioFormatOptions.newBuilder()
                                            .setRawAudio(Stt.RawAudio.newBuilder()
                                                    .setAudioChannelCount(1)
                                                    .setSampleRateHertz(sampleRate)
                                                    .setAudioEncoding(Stt.RawAudio.AudioEncoding.LINEAR16_PCM)
                                                    .build()))
                                    .setAudioProcessingType(Stt.RecognitionModelOptions.AudioProcessingType.REAL_TIME)
                                    .build())
                            .build())
                    .build();
        }
    }
}