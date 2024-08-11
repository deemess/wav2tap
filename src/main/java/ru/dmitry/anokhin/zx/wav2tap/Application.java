package ru.dmitry.anokhin.zx.wav2tap;

import com.github.psambit9791.wavfile.WavFile;
import com.github.psambit9791.wavfile.WavFileException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class Application {

    enum State {
        POSITIVE, NEGATIVE
    }

    enum Pulse {
        ZERO, ONE, PILOT, UNKNOWN
    }

    //only for 44100 Hz //TODO: support other sample rates
    static int tick = 23; //1 sec / 44100 Hz = 23 us

    static int zero_min = 250/tick;
    static int zero_max = 500/tick;
    static int one_min = 500/tick;
    static int one_max = 1000/tick;
    static int pilot_min = 1000/tick;
    static int pilot_max = 2000/tick;

    static double noizeFilter = 0.05; //5 percentage

    //zero = (0.25-0.5 ms)
    //one = (0.5-1 ms)
    //pilot = (1-2 ms)

    public static void main(String[] args) throws IOException, WavFileException {
        if(args.length != 2) {
            System.out.println("Usage: java -jar wav2tap filename.wav filename.tap");
            return;
        }

        String filename = args[0];
        String outputFilename = args[1];
        WavFile wavFile = WavFile.openWavFile(new File(filename));
        var rate = wavFile.getSampleRate();
        var channels = wavFile.getNumChannels();
        var frames = wavFile.getNumFrames();
        var bits = wavFile.getValidBits();

        System.out.println("Sample rate: " + rate);
        System.out.println("Number of channels: " + channels);
        System.out.println("Number of frames: " + frames);
        System.out.println("Number of bits: " + bits);

        int noizeabs = (int) ((Math.pow(2, bits)/2) * noizeFilter);
        int average = (int) (Math.pow(2, bits) / 2);
        var buff = new int[1024*channels];

        State current = State.NEGATIVE;
        int pulseLen = 0;

        List<Pulse> pulses = new ArrayList<>();
        Pulse lastPulse = Pulse.UNKNOWN;

        int last3 = average;
        int last2 = average;
        int last1 = average;

        for(int i=0; i< frames; i++) {
            var sample = wavFile.readFrames(buff, 1024);

            for(int j=0; j<sample; j += channels) {
                int curvalue = channels ==1 ? buff[j] : (buff[j] + buff[j+1])/2;

                last1 = last2;
                last2 = last3;
                last3 = curvalue;

                //noize filter
                if(Math.abs(curvalue - average) < noizeabs) {
                    if(Math.abs(((last1+last2+last3)/3) - average) < noizeabs) {
                        pulseLen = 0;
                        continue;
                    }
                }

                int p = curvalue > average ? 1 : 0;

                if(current == State.POSITIVE) {
                    if(p == 0) {
                        current = State.NEGATIVE;
                    } else {
                        pulseLen++;
                    }
                } else if(current == State.NEGATIVE) {
                    if (p == 1) {
                        current = State.POSITIVE;
                        Pulse pulse = getPulseType(pulseLen);
                        if (lastPulse != Pulse.PILOT || pulse != Pulse.PILOT) {
                            pulses.add(pulse);
                        }
                        lastPulse = pulse;
                        pulseLen = 0;
                    } else {
                        pulseLen++;
                    }
                }
            }

        }

        wavFile.close();
        writeFile(pulses, outputFilename);

    }

    static Pulse getPulseType(int pulseLen) {
        if(pulseLen > zero_min && pulseLen < zero_max) {
            return Pulse.ZERO;
        } else if(pulseLen > one_min && pulseLen < one_max) {
            return Pulse.ONE;
        } else if(pulseLen > pilot_min && pulseLen < pilot_max) {
            return Pulse.PILOT;
        } else {
            return Pulse.UNKNOWN;
        }
    }

    static void writeFile(List<Pulse> pulses, String filename) throws IOException {
        try (var file = new FileOutputStream(filename)) {
            for(int i=0; i<pulses.size(); i++) {
                try {
                    List<Integer> block = new ArrayList<>();
                    i = findPilot(pulses, i);
                    i = findNonPilot(pulses, i);
                    int[] b = new int[]{0};
                    if (i < pulses.size() && pulses.get(i++) == Pulse.ZERO) {
                        try {
                            while (i < pulses.size() && pulses.get(i) != Pulse.UNKNOWN && pulses.get(i) != Pulse.PILOT) {
                                i = readByte(pulses, i, b);
                                block.add(b[0]);
                            }
                            i--;
                        } catch (Exception e) {
                            System.out.println("Error: " + e);
                        }
                        writeBlock(block, file);
                    }
                } catch (Exception e) {
                    System.out.println("Error: " + e);
                }
            }
        }
    }

    static void writeBlock(List<Integer> bytes, FileOutputStream oStream) throws IOException {
        if(bytes.isEmpty()) {
            return;
        }
        System.out.println("Writing block size " + bytes.size());
        int size = bytes.size();
        byte h = (byte) ((size >> 8) & 0xFF);
        byte l = (byte) (size & 0xFF);
        oStream.write(l);
        oStream.write(h);
        for(int i=0; i<size; i++) {
            oStream.write((byte) (bytes.get(i) & 0xFF));
        }
    }

    static int readByte(List<Pulse> pulses, int currentPosition, int[] b) {
        int read = 0;
        int bits = 0;
        for(int i=currentPosition; i<pulses.size(); i++) {
            if(bits == 8) {
                b[0] = read;
                return i;
            }
            if(pulses.get(i) == Pulse.UNKNOWN || pulses.get(i) == Pulse.PILOT) {
                throw new RuntimeException("Non valid pulse while reading byte");
            }
            if(pulses.get(i) == Pulse.ONE) {
                read |= (1 << (7-bits));
            }
            bits++;
        }
        return pulses.size();
    }

    static int findNonPilot(List<Pulse> pulses, int currentPosition) {
        for(int i=currentPosition; i<pulses.size(); i++) {
            if(pulses.get(i) != Pulse.PILOT) {
                return i;
            }
        }
        return pulses.size();
    }

    static int findPilot(List<Pulse> pulses, int currentPosition) {
        for(int i=currentPosition; i<pulses.size(); i++) {
            if(pulses.get(i) == Pulse.PILOT) {
                return i;
            }
        }
        return pulses.size();
    }

}