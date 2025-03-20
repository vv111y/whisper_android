package com.whispertflite.utils;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Utility class for converting audio files to formats compatible with Whisper model
 */
public class AudioConverter {
    private static final String TAG = "AudioConverter";
    
    /**
     * Converts an audio file to WAV format with specified parameters
     * 
     * @param inputPath Path to input audio file
     * @param outputPath Path where output WAV file should be saved
     * @param sampleRate Target sample rate in Hz
     * @param channels Number of channels (1 for mono, 2 for stereo)
     * @param bitsPerSample Bits per sample (16 for 16-bit PCM)
     * @return true if conversion was successful
     */
    public static boolean convertToWav(String inputPath, String outputPath, 
                                      int sampleRate, int channels, int bitsPerSample) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        
        try {
            // Set up MediaExtractor to read the source file
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);
            
            // Select the first audio track
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                Log.e(TAG, "No audio track found in file: " + inputPath);
                return false;
            }
            extractor.selectTrack(trackIndex);
            
            // Get the audio format details
            MediaFormat originalFormat = extractor.getTrackFormat(trackIndex);
            Log.d(TAG, "Original format: " + originalFormat);
            
            // Create output format for PCM
            MediaFormat outputFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_RAW, sampleRate, channels);
            outputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            
            // Get decoder for this format
            String decoderName = new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findDecoderForFormat(originalFormat);
            if (decoderName == null) {
                Log.e(TAG, "No decoder found for format: " + originalFormat);
                return false;
            }
            
            decoder = MediaCodec.createByCodecName(decoderName);
            configureCodec(decoder, originalFormat);
            
            // Prepare output file
            FileOutputStream outputStream = new FileOutputStream(outputPath);
            
            // Write WAV header
            writeWavHeader(outputStream, sampleRate, channels, bitsPerSample);
            
            // Start decoding BEFORE getting buffers
            decoder.start();
            
            // Set up buffers for processing - IMPORTANT: Do this AFTER decoder.start()
            // Use the modern API (getInputBuffer/getOutputBuffer) for newer Android versions
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            
            // Process and convert audio data
            while (!sawOutputEOS) {
                // Handle input
                if (!sawInputEOS) {
                    int inputBufIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer;
                        // Use the appropriate method to get the input buffer
                        inputBuffer = decoder.getInputBuffer(inputBufIndex);
                        
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEOS = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }
                
                // Handle output
                int outputBufIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputBufIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                    }
                    
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufIndex);
                        
                        if (outputBuffer != null) {
                            outputBuffer.position(info.offset);
                            outputBuffer.limit(info.offset + info.size);
                            
                            // Write decoded PCM data to file
                            byte[] buffer = new byte[info.size];
                            outputBuffer.get(buffer);
                            
                            // Resample if needed
                            if (originalFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) && 
                                originalFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) &&
                                (originalFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sampleRate ||
                                originalFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels)) {
                                
                                buffer = resampleAudio(buffer, 
                                        originalFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                        originalFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                        sampleRate, channels);
                            }
                            
                            if (buffer != null) {
                                outputStream.write(buffer);
                            }
                        }
                    }
                    
                    decoder.releaseOutputBuffer(outputBufIndex, false);
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Output format changed to " + decoder.getOutputFormat());
                }
            }
            
            // Update WAV header with actual data size
            updateWavHeader(outputStream, outputPath);
            outputStream.close();
            
            Log.d(TAG, "Audio conversion completed successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting audio: " + e.getMessage(), e);
            e.printStackTrace();
            return false;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (IllegalStateException e) {
                    // Ignore if the decoder wasn't started
                    Log.w(TAG, "Error stopping decoder: " + e.getMessage());
                }
            }
        }
    }
    
    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }
    
    private static void configureCodec(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, 0);
    }
    
    private static void writeWavHeader(FileOutputStream outputStream, int sampleRate, 
                                       int channels, int bitsPerSample) throws IOException {
        // RIFF header
        outputStream.write("RIFF".getBytes()); // ChunkID
        outputStream.write(new byte[4]); // ChunkSize (placeholder, will update later)
        outputStream.write("WAVE".getBytes()); // Format
        
        // fmt subchunk
        outputStream.write("fmt ".getBytes()); // Subchunk1ID
        writeInt(outputStream, 16); // Subchunk1Size (16 for PCM)
        writeShort(outputStream, (short) 1); // AudioFormat (1 for PCM)
        writeShort(outputStream, (short) channels); // NumChannels
        writeInt(outputStream, sampleRate); // SampleRate
        writeInt(outputStream, sampleRate * channels * bitsPerSample / 8); // ByteRate
        writeShort(outputStream, (short) (channels * bitsPerSample / 8)); // BlockAlign
        writeShort(outputStream, (short) bitsPerSample); // BitsPerSample
        
        // data subchunk
        outputStream.write("data".getBytes()); // Subchunk2ID
        outputStream.write(new byte[4]); // Subchunk2Size (placeholder, will update later)
    }
    
    private static void updateWavHeader(FileOutputStream outputStream, String filePath) throws IOException {
        File file = new File(filePath);
        long fileSize = file.length();
        
        // Update RIFF chunk size
        RandomAccessFileHelper.writeInt(filePath, 4, (int) (fileSize - 8));
        
        // Update data subchunk size
        RandomAccessFileHelper.writeInt(filePath, 40, (int) (fileSize - 44));
    }
    
    private static void writeInt(FileOutputStream outputStream, int value) throws IOException {
        byte[] data = new byte[4];
        data[0] = (byte) (value & 0xFF);
        data[1] = (byte) ((value >> 8) & 0xFF);
        data[2] = (byte) ((value >> 16) & 0xFF);
        data[3] = (byte) ((value >> 24) & 0xFF);
        outputStream.write(data);
    }
    
    private static void writeShort(FileOutputStream outputStream, short value) throws IOException {
        byte[] data = new byte[2];
        data[0] = (byte) (value & 0xFF);
        data[1] = (byte) ((value >> 8) & 0xFF);
        outputStream.write(data);
    }
    
    /**
     * Simple resampling implementation (not high quality but functional)
     */
    private static byte[] resampleAudio(byte[] input, int srcSampleRate, int srcChannels, 
                                      int dstSampleRate, int dstChannels) {
        // Just use a basic downsampling approach for now
        // This is very basic and doesn't handle all cases well
        
        if (input == null || input.length == 0) {
            return null;
        }
        
        // For simplicity, only support 16-bit audio (2 bytes per sample)
        int bytesPerSample = 2;
        int inputSamples = input.length / (bytesPerSample * srcChannels);
        
        // Calculate output buffer size
        double ratio = (double) dstSampleRate / srcSampleRate;
        int outputSamples = (int) (inputSamples * ratio);
        int outputSize = outputSamples * dstChannels * bytesPerSample;
        byte[] output = new byte[outputSize];
        
        // Wrap the input bytes in a buffer for easier access
        ShortBuffer inputBuffer = ByteBuffer.wrap(input)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer();
        
        // Wrap the output buffer
        ShortBuffer outputBuffer = ByteBuffer.wrap(output)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer();
        
        // Handle resampling
        for (int i = 0; i < outputSamples; i++) {
            // Map output sample index to input sample index
            int inputIndex = (int) (i / ratio);
            if (inputIndex >= inputSamples) {
                inputIndex = inputSamples - 1;
            }
            
            // For mono output (from any number of input channels)
            if (dstChannels == 1) {
                int sum = 0;
                for (int c = 0; c < srcChannels; c++) {
                    sum += inputBuffer.get(inputIndex * srcChannels + c);
                }
                outputBuffer.put((short) (sum / srcChannels));
            } 
            // For stereo output from mono input
            else if (dstChannels == 2 && srcChannels == 1) {
                short sample = inputBuffer.get(inputIndex);
                outputBuffer.put(sample); // Left
                outputBuffer.put(sample); // Right
            }
            // For unchanged channel count
            else if (dstChannels == srcChannels) {
                for (int c = 0; c < dstChannels; c++) {
                    outputBuffer.put(inputBuffer.get(inputIndex * srcChannels + c));
                }
            }
        }
        
        return output;
    }
    
    /**
     * Helper class for random access to files
     */
    private static class RandomAccessFileHelper {
        public static void writeInt(String filePath, int position, int value) throws IOException {
            java.io.RandomAccessFile file = new java.io.RandomAccessFile(filePath, "rw");
            file.seek(position);
            file.write((byte) (value & 0xFF));
            file.write((byte) ((value >> 8) & 0xFF));
            file.write((byte) ((value >> 16) & 0xFF));
            file.write((byte) ((value >> 24) & 0xFF));
            file.close();
        }
    }
}
