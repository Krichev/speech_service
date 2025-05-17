package yandex.cloud.speechkit.examples;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import yandex.cloud.api.ai.stt.v3.RecognizerGrpc;
import yandex.cloud.api.ai.stt.v3.Stt;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import yandex.cloud.api.ai.stt.v3.RecognizerGrpc;
import yandex.cloud.api.ai.stt.v3.Stt;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SttV3Client {

    private final RecognizerGrpc.RecognizerStub client;

    public SttV3Client(String host, int port, String apikey) {
        client = sttV3Client(host, port, apikey);
    }

    /**
     * Recognize audio from file
     */
    public String recognize(File wav) throws UnsupportedAudioFileException, IOException {
        // bidirectional streaming; we init a response observer then get a request observer from client
        var responseObserver = new SttStreamObserver();
        var requestObserver = client.recognizeStreaming(responseObserver);

        // read audio file: parse audio format settings and get raw audio bytes
        var audioFormat = AudioSystem.getAudioFileFormat(wav);
        var bytes = AudioSystem.getAudioInputStream(wav).readAllBytes();

        var frameRateHertz = (int) audioFormat.getFormat().getFrameRate();

        System.out.println("sending initial request");
        // sending one initial request with recognition parameters to the server
        requestObserver.onNext(createInitialRequest(frameRateHertz, "ru-RU"));

        // chunk size in bytes : 0.2 seconds * 2 bytes per frame * frame rate
        var chunkStart = 0;
        var chunkSize = (int) (frameRateHertz * 2 * 0.2);
        while (chunkStart < bytes.length) {
            chunkSize = Math.min(chunkSize, bytes.length - chunkStart);
            // then send multiple requests with chunks of audio stream
            var reqBuilder = Stt.StreamingRequest.newBuilder();
            reqBuilder.getChunkBuilder().setData(ByteString.copyFrom(bytes, chunkStart, chunkSize));
            requestObserver.onNext(reqBuilder.build());
            chunkStart = chunkStart + chunkSize;
        }
        // notify server that we are done streaming.
        requestObserver.onCompleted();
        System.out.println("Done sending");

        // wait for responses to come
        return responseObserver.awaitResult(5);
    }

    /**
     * Start a streaming recognition session
     * @param responseObserver Observer for handling server responses
     * @return RequestObserver for sending audio chunks
     */
    public StreamObserver<Stt.StreamingRequest> startStreaming(StreamObserver<Stt.StreamingResponse> responseObserver) {
        return client.recognizeStreaming(responseObserver);
    }

    /**
     * Create an initial STT request with configuration
     */
    public static Stt.StreamingRequest createInitialRequest(long frameRateHertz, String languageCode) {
        var builder = Stt.StreamingRequest.newBuilder();
        builder.getSessionOptionsBuilder()
                .setRecognitionModel(Stt.RecognitionModelOptions.newBuilder()
                        .setLanguageRestriction(Stt.LanguageRestrictionOptions.newBuilder()
                                .addLanguageCode(languageCode)
                                .setRestrictionType(Stt.LanguageRestrictionOptions.LanguageRestrictionType.WHITELIST)
                                .build())
                        .setAudioFormat(Stt.AudioFormatOptions.newBuilder()
                                .setRawAudio(Stt.RawAudio.newBuilder()
                                        .setAudioChannelCount(1)
                                        .setSampleRateHertz(frameRateHertz)
                                        .setAudioEncoding(Stt.RawAudio.AudioEncoding.LINEAR16_PCM)
                                        .build()))
                        .setAudioProcessingType(Stt.RecognitionModelOptions.AudioProcessingType.REAL_TIME)
                        .build());
        return builder.build();
    }

    /**
     * Create an audio chunk request
     */
    public static Stt.StreamingRequest createAudioChunkRequest(byte[] audioData) {
        return Stt.StreamingRequest.newBuilder()
                .setChunk(Stt.AudioChunk.newBuilder()
                        .setData(ByteString.copyFrom(audioData))
                        .build())
                .build();
    }

    static class SttStreamObserver implements StreamObserver<Stt.StreamingResponse> {

        private StringBuilder result = new StringBuilder();

        private static CountDownLatch count = new CountDownLatch(1);

        @Override
        public void onNext(Stt.StreamingResponse response) {
            response.getFinal()
                    .getAlternativesList()
                    .forEach(a -> result.append(a.getText().trim()).append(" "));
        }

        @Override
        public void onError(Throwable t) {
            System.out.println("Stt streaming error occurred " + t);
            t.printStackTrace();
        }

        @Override
        public void onCompleted() {
            System.out.println("Stt stream completed");
            count.countDown();
        }

        String awaitResult(int timeoutSeconds) {
            try {
                count.await(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return result.toString();
        }
    }

    private static RecognizerGrpc.RecognizerStub sttV3Client(String host, int port, String apiKey) {
        var channel = ManagedChannelBuilder
                .forAddress(host, port)
                .build();

        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Api-Key " + apiKey);
        var requestId = UUID.randomUUID().toString();
        headers.put(Metadata.Key.of("x-client-request-id", Metadata.ASCII_STRING_MARSHALLER), requestId);

        return RecognizerGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    public static void main(String[] args) throws Exception {
        File wav;
        if (args.length > 0) {
            // get the wav file from arguments
            wav = new File(args[0]);
        } else {
            // if it's not set, use the default one
            // (in russian, "здравствуйте глеб меня зовут анастасия чем я могу вам помочь")
            wav = new File(SttV3Client.class.getClassLoader().getResource("sample.wav").toURI());
        }

        // $API_KEY env must be set
        var apikey = System.getenv("API_KEY");

        // init grpc connection
        var client = new SttV3Client("stt.api.cloud.yandex.net", 443, apikey);

        var response = client.recognize(wav);
        System.out.println("Recognized text is " + response);
    }
}