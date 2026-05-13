package com.hanif.bijoykeyboard;

import java.util.HashMap;
import java.util.Map;

public class Bijoymaper {
    public static final Map<String, String> MAP = new HashMap<>();

    static {
        // --- স্বরবর্ণ ও কার চিহ্ন ---
        MAP.put("f", "\u09BE");          // া কার
        MAP.put("Shift+F", "\u0985");    // অ
        MAP.put("d", "\u09BF");          // ি (ই-কার)
        MAP.put("Shift+D", "\u09C0");    // ী (ঈ-কার)
        MAP.put("s", "\u09C1");          // ু (উ-কার)
        MAP.put("Shift+S", "\u09C2");    // ূ (ঊ-কার)
        MAP.put("a", "\u09C3");          // ৃ (ঋ-কার)
        MAP.put("Shift+A", "\u09B0\u09CD"); // রেফ
        MAP.put("c", "\u09C7");          // ে (এ-কার)
        MAP.put("Shift+C", "\u09C8");    // ৈ (ঐ-কার) - এখানে ডাবল স্ল্যাশ ফিক্স করা হয়েছে
        MAP.put("x", "\u0993");          // (ও-) - মনে রাখবেন, স্বরবর্ণ ও-এর জন্য বিজয় লজিক কাজ করবে
        MAP.put("Shift+X", "\u09CC");    // ৌ (ৌ-কার) - এখানে \U বড় হাতের ছিল, ছোট হাতে ফিক্স করা হয়েছে

        // --- ব্যঞ্জনবর্ণ ---
        MAP.put("j", "\u0995");          // ক
        MAP.put("Shift+J", "\u0996");    // খ
        MAP.put("o", "\u0997");          // গ
        MAP.put("Shift+O", "\u0998");    // ঘ
        MAP.put("q", "\u0999");          // ঙ
        MAP.put("Shift+Q", "\u0982");    // ং (অনুস্বার)

        MAP.put("y", "\u099A");          // চ
        MAP.put("Shift+Y", "\u099B");    // ছ
        MAP.put("u", "\u099C");          // জ
        MAP.put("Shift+U", "\u099D");    // ঝ
        MAP.put("i", "\u09B9");          // হ
        MAP.put("Shift+I", "\u099E");    // ঞ

        MAP.put("t", "\u099F");          // ট
        MAP.put("Shift+T", "\u09A0");    // ঠ
        MAP.put("e", "\u09A1");          // ড
        MAP.put("Shift+E", "\u09A2");    // ঢ
        MAP.put("b", "\u09A8");          // ন
        MAP.put("Shift+B", "\u09A3");    // ণ

        MAP.put("k", "\u09A4");          // ত
        MAP.put("Shift+K", "\u09A5");    // থ
        MAP.put("l", "\u09A6");          // দ
        MAP.put("Shift+L", "\u09A7");    // ধ

        MAP.put("r", "\u09AA");          // প
        MAP.put("Shift+R", "\u09AB");    // ফ
        MAP.put("h", "\u09AC");          // ব
        MAP.put("Shift+H", "\u09AD");    // ভ
        MAP.put("m", "\u09AE");          // ম

        MAP.put("p", "\u09DC");          // ড়
        MAP.put("Shift+P", "\u09DD");    // ঢ়
        MAP.put("v", "\u09B0");          // র
        MAP.put("Shift+V", "\u09B2");    // ল

        MAP.put("n", "\u09B8");          // স
        MAP.put("Shift+N", "\u09B7");    // ষ
        MAP.put("Shift+M", "\u09B6");    // শ

        MAP.put("w", "\u09AF");          // য
        MAP.put("Shift+W", "\u09DF");    // য়
        MAP.put("z", "\u09CD\u09B0");    // ্র (র-ফলা)
        MAP.put("Shift+Z", "\u09CD\u09AF"); // ্য (য-ফলা)

        // --- বিশেষ চিহ্ন ও হসন্ত ---
        MAP.put("g", "\u09CD");          // ্ (হসন্ত)
        MAP.put("Shift+G", "\u0964");    // । (দাড়ি)
        MAP.put("\\", "\u09CE");         // ৎ (খণ্ড-ত)
        MAP.put("Shift+\\", "\u0983");
        MAP.put("Shift+|", "\u0983");   // ← এটা add করুন
        MAP.put("Shift+7", "\u0981");    // ঁ (চন্দ্রবিন্দু)
        MAP.put("Shift+9", "\u09ce");    // ৎ (external keyboard)
        MAP.put("Shift+0", "\u0983");    // ঃ (external keyboard)
        // সংখ্যাগুলো (বাংলা মোডেও যেন কাজ করে)
        MAP.put("1", "\u09E7"); MAP.put("2", "\u09E8"); MAP.put("3", "\u09E9");
        MAP.put("4", "\u09EA"); MAP.put("5", "\u09EB"); MAP.put("6", "\u09EC");
        MAP.put("7", "\u09ED"); MAP.put("8", "\u09EE"); MAP.put("9", "\u09EF"); MAP.put("0", "\u09E6");
    }

    public static String getUnicode(String key, boolean isShift) {
        String cleanKey = key.toLowerCase();
        String lookup = isShift ? "Shift+" + cleanKey.toUpperCase() : cleanKey;

        if (MAP.containsKey(lookup)) {
            return MAP.get(lookup);
        }
        return key;
    }
}
