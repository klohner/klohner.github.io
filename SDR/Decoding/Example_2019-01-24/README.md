# Decoding ASK/OOK_PPM Signals with URH and rtl_433

Let's decode some rtl_433 Signal I/Q Sample Files using Universal Radio Hacker and the rtl_433 Flexible General Purpose Decoder!

A response to [https://groups.google.com/forum/#!msg/rtl_433/u2d7SMntzaE/AfwGxLqJFAAJ](https://groups.google.com/forum/#!msg/rtl_433/u2d7SMntzaE/AfwGxLqJFAAJ)

## [g001_433.92M_250k.complex16u](g001_433.92M_250k.complex16u)

### Decoding with [Universal Radio Hacker](https://github.com/jopohl/urh) version 2.5.5

- Load file into URH.

- I changed the Signal View to Spectrogram, highlighted the signal, right-click and choose Apply Bandpass Filter 
  to further reduce noise.

![g001_433.92M_250k in URH](g001_433.92M_250k.jpg)

- I changed Bit length to something small (10, in this case), Error tolerance to something
  even smaller than that (1, in this case).  I clicked the wrench icon next to the "ASK" modulation type and made sure
  the Pause Threshold was 0 (disabled).  I then zoomed in on part of the signal.

- By selecting a chunk of the "1" and "0" symbol bits, I was able to highlight a section of what looks like exactly 6 symbols
  in the signal.

![g001_433.92M_250k_decode_1.jpg in URH](g001_433.92M_250k_decode_1.jpg)

- The display says this is "1512" samples in length.  Divided by 6, this gives us a symbol Bit Length of "252".  So
I put "252" into the Bit Length field and set the Error Tolerance to a small percentage of this at (10, in this case)

- I clicked the wrench icon and changed the Pause Threshold to 4 so that URH will break aparts signal frames when there
  are big groups of "0" symbols.

![g001_433.92M_250k_decode_2.jpg in URH](g001_433.92M_250k_decode_2.jpg)

- Signal seems to contain frames with a symbol pattern of 38 symbols: "11111001010101000101000101010001010101".

- To decode that pattern, I then went to the Analysis tab in URH and made sure only that signal was checked in the Group.

![g001_433.92M_250k_analysis_1 in URH](g001_433.92M_250k_analysis_1.jpg)

- Visually, the symbol pattern to me looks like symbols "11111001" as a prefix, and data looks like a 0 bit when a pattern of "01"
  appears, and a 1 bit for a symbol pattern of "00001".  This is just an educated guess by visually looking for patterns in the data.

- I changed the Configure Decoding to "..." to bring up the dialog to create a new decoding.  I dragged the "Cut before/after" function
  into the Decoder panel and set it to "Cut before" position "8" (to remove the "11111001" prefix pattern).

- I dragged the "Invert" function to the next position in the Decoder panel to invert the bits.

- Now the the bits are inverted, I dragged the "Morse Code" function to the Decoder panel and configured it so the "Maximum length of 1-sequence for: Low" is "1"
  and the "Minimum length of 1-sequence for: High" is "3".

- I used "Save as..." to name this decoding as "My_Decoding_Invert_Morse" and closed the Decoding editor window.

- I selected this decoding and made sure it was applied to the frame patterns.

![g001_433.92M_250k_analysis_2 in URH](g001_433.92M_250k_analysis_2.jpg)

- The resulting data message is "000101001000" or `0x148` in hex.

### Decoding in [rtl_433](https://github.com/merbanan/rtl_433) (using a build as of 2019-01-22)

- First, let's try to dream up what the Flex specification for this signal might be.  Let's take a look at the output
of the `rtl_433 -X help` command:

```
Use -X <spec> to add a flexible general purpose decoder.

<spec> is "key=value[,key=value...]"
Common keys are:
        name=<name> (or: n=<name>)
        modulation=<modulation> (or: m=<modulation>)
        short=<short> (or: s=<short>)
        long=<long> (or: l=<long>)
        sync=<sync> (or: y=<sync>)
        reset=<reset> (or: r=<reset>)
        gap=<gap> (or: g=<gap>)
        tolerance=<tolerance> (or: t=<tolerance>)
where:
<name> can be any descriptive name tag you need in the output
<modulation> is one of:
        OOK_MC_ZEROBIT :  Manchester Code with fixed leading zero bit
        OOK_PCM :         Pulse Code Modulation (RZ or NRZ)
        OOK_PPM :         Pulse Position Modulation
        OOK_PWM :         Pulse Width Modulation
        OOK_DMC :         Differential Manchester Code
        OOK_PIWM_RAW :    Raw Pulse Interval and Width Modulation
        OOK_PIWM_DC :     Differential Pulse Interval and Width Modulation
        OOK_MC_OSV1 :     Manchester Code for OSv1 devices
        FSK_PCM :         FSK Pulse Code Modulation
        FSK_PWM :         FSK Pulse Width Modulation
        FSK_MC_ZEROBIT :  Manchester Code with fixed leading zero bit
<short>, <long>, <sync>, and <reset> are the timings for the decoder in ┬╡s
PCM     short: Nominal width of pulse [us]
         long: Nominal width of bit period [us]
PPM     short: Nominal width of '0' gap [us]
         long: Nominal width of '1' gap [us]
PWM     short: Nominal width of '1' pulse [us]
         long: Nominal width of '0' pulse [us]
         sync: Nominal width of sync pulse [us] (optional)
          gap: Maximum gap size before new row of bits [us]
    tolerance: Maximum pulse deviation [us] (optional)
        reset: Maximum gap size before End Of Message [us].
Available options are:
        bits=<n> : only match if at least one row has <n> bits
        rows=<n> : only match if there are <n> rows
        repeats=<n> : only match if some row is repeated <n> times
                use opt>=n to match at least <n> and opt<=n to match at most <n>
        invert : invert all bits
        reflect : reflect each byte (MSB first to MSB last)
        match=<bits> : only match if the <bits> are found
        preamble=<bits> : match and align at the <bits> preamble
                <bits> is a row spec of {<bit count>}<bits as hex number>
        countonly : suppress detailed row output

E.g. -X "n=doorbell,m=OOK_PWM,s=400,l=800,r=7000,g=1000,match={24}0xa9878c,repeats>=3"
```

- I'm going to guess the modulation of this signal is "OOK_PPM" (Pulse Position Modulation) 
  because data bits seem to be encoded as the length of signal gaps (the "0" signals) between 
  signal pulses (the "1" signals).

- From our analysis, those pulses are 252 samples long.  The file we're analysing was sampled
  at "250k" samples per second, according to the filename, so the duration of each symbol of
  a pulse or gap is about 1008 microseconds (μs) in duration.

- OOK_PPM needs to know the "Nominal width of '0' gap [us]" and based on the signal we looked
  at before, I know that a 0 data bit is a "0" signal (a gap) only one symbol in duration (1008μs)
  before a pulse (a "1" symbol).  So, we have so far:

  `n=SAMPLE1,m=OOK_PPM,s=1008`

- OOK_PPM needs to know the "Nominal width of '1' gap [us]" and based on the signal we looked
  at before, I know that a 1 data bit is a "0" signal (a gap) three symbols in duration (3024μs)
  before a pulse (a "1" symbol). 

  `n=SAMPLE1,m=OOK_PPM,s=1008,l=3024`

- OOK_PPM also needs to know the reset duration.  We know that any gap longer than 3 symbols
  is outside the frame data, so let's round that up a bit and say 3200μs.

  `n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200`

- Let's give that a try on our signal:  

  `rtl_433 -R 0 -r g001_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200`

![g001_433.92M_250k_rtl_433_1.jpg in URH](g001_433.92M_250k_rtl_433_1.jpg)

- Hey, that's looking pretty good.  rtl_433 is trying to decode the prefix, though, instead
  of chopping it off, and my guess is that it's looking at that initial "11111001" symbol
  string and decoding that "001" as a "0" bit.  I'll guess that it's because it's applying
  some default value of tolerance to those durations and deciding that "001" looks like a 
  "0" bit.  Let's specify a tolerance of 100μs and see what happens.

  `rtl_433 -R 0 -r g001_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200,t=100`

![g001_433.92M_250k_rtl_433_2.jpg in URH](g001_433.92M_250k_rtl_433_2.jpg)

- Looks great!  But it seems rtl_433 doesn't decode this signal by default, though.  `rtl_433 -G -r g001_433.92M_250k.complex16u` doesn't give us any result.
  Without more data points, it's hard to say if the device that sent this signal varies the signal at all or what this signal means.


## [g002_433.92M_250k.complex16u](g002_433.92M_250k.complex16u)

- Use the same process as we did before.  It looks like a very similar signal.  URH decodes it to these symbols:

```
1111100101000100010001010001010100010001010001 [Pause: 13118 samples]
1111100101000100010001010001010100010001010001 [Pause: 155931 samples]
11111001010101000101000101010001010101 [Pause: 15637 samples]
11111001010101000101000101010001010101 [Pause: 28777 samples]
```

- And if we apply the same decoder in the Analysis tab, we get this data:

![g002_433.92M_250k_analysis_1 in URH](g002_433.92M_250k_analysis_1.jpg)

- The first two frames decode to `0x74d` and the last two decode to `0x148` so maybe the device does vary the signal based on something...  But still, no idea what it means.

- `rtl_433 -R 0 -r g002_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200,t=100`

![g002_433.92M_250k_rtl_433_1 in URH](g002_433.92M_250k_rtl_433_1.jpg)

## [g003_433.92M_250k.complex16u](g003_433.92M_250k.complex16u)

- This looks like the same kind of signal and gives us a couple more `0x74d` data frames.

- `rtl_433 -R 0 -r g003_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200,t=100`

![g003_433.92M_250k_rtl_433_1 in URH](g003_433.92M_250k_rtl_433_1.jpg)

## [g025_433.92M_250k.complex16u](g025_433.92M_250k.complex16u)

![g025_433.92M_250k in URH](g025_433.92M_250k_decode_1.jpg)

- It looks like a weak signal that got truncated.  I'm not even going to bother with it.

## [g026_433.92M_250k.complex16u](g026_433.92M_250k.complex16u)

![g026_433.92M_250k in URH](g026_433.92M_250k_decode_1.jpg)

- It looks like noise.  Next...

## [g027_433.92M_250k.complex16u](g027_433.92M_250k.complex16u)

- It looks like almost the same signal as our very first sample file.  `rtl_433 -R 0 -r g027_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200,t=100` gives us "0x148" data frames.

## [g028_433.92M_250k.complex16u](g028_433.92M_250k.complex16u)

- `rtl_433 -R 0 -r g028_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200,t=100` gives us "0x148" data frames.

## [g029_433.92M_250k.complex16u](g029_433.92M_250k.complex16u)

- `rtl_433 -R 0 -r g028_433.92M_250k.complex16u -X n=SAMPLE1,m=OOK_PPM,s=1008,l=3024,r=3200,t=100` gives us "0x74d" data frames.
