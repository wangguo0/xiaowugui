package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Vod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 同名影片「是否同一内容」判定：5 维指纹（类型桶/年份/地区/导演/主演）。
// 强证据（类型桶冲突、年份相差≥2、导演双方有值且零重合）任一命中即判不同；
// 弱证据（年份差1、地区无交集、主演无交集）累计≥2 判不同；
// 字段缺失的维度弃权，全部弃权时保守视为同一内容（宁合勿错拆）。
public class VodMatcher {

    private static final int TYPE_UNKNOWN = 0;
    private static final int TYPE_ANIME = 1;
    private static final int TYPE_DRAMA = 2;
    private static final int TYPE_MOVIE = 3;
    private static final int TYPE_VARIETY = 4;

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern SPLIT = Pattern.compile("[,，、/|\\s]+");
    private static final Set<String> VOID = new HashSet<>(Arrays.asList("暂无", "未知", "不详", "无", "其他", "其它", "-", "—"));

    private VodMatcher() {
    }

    public static boolean isConflict(Vod a, Vod b) {
        if (a == null || b == null) return false;
        int bucketA = typeBucket(a.getTypeName());
        int bucketB = typeBucket(b.getTypeName());
        if (bucketA != TYPE_UNKNOWN && bucketB != TYPE_UNKNOWN && bucketA != bucketB) return true;
        int yearA = year(a.getYear());
        int yearB = year(b.getYear());
        if (yearA > 0 && yearB > 0 && Math.abs(yearA - yearB) >= 2) return true;
        // 导演为强证据：双方都有导演数据且零重合直接判不同；任一方缺失则弃权（disjoint 返回 false）
        if (disjoint(a.getDirector(), b.getDirector(), false)) return true;
        int score = 0;
        if (yearA > 0 && yearB > 0 && yearA != yearB) score += 1;
        if (disjoint(a.getArea(), b.getArea(), true)) score += 1;
        if (disjoint(a.getActor(), b.getActor(), false)) score += 1;
        return score >= 2;
    }

    private static int typeBucket(String type) {
        String t = normalize(type);
        if (t.isEmpty()) return TYPE_UNKNOWN;
        if (t.contains("综艺") || t.contains("真人秀") || t.contains("脱口秀")) return TYPE_VARIETY;
        if (t.contains("动漫") || t.contains("动画") || t.contains("番剧") || t.contains("国漫") || t.contains("日漫")) return TYPE_ANIME;
        if (t.contains("电影") || t.contains("影片") || t.endsWith("片")) return TYPE_MOVIE;
        if (t.contains("剧")) return TYPE_DRAMA;
        return TYPE_UNKNOWN;
    }

    // 类型冲突判定（快搜过滤专用）：typeName 优先，站点未返回类型时退化为片名关键词提示。
    // 两侧桶均已知且不同（如当前为动漫、候选为真人版）即判冲突；任一侧未知则不淘汰。
    public static boolean isTypeConflict(Vod a, Vod b) {
        if (a == null || b == null) return false;
        int bucketA = bucketWithHint(a);
        int bucketB = bucketWithHint(b);
        return bucketA != TYPE_UNKNOWN && bucketB != TYPE_UNKNOWN && bucketA != bucketB;
    }

    private static int bucketWithHint(Vod vod) {
        int bucket = typeBucket(vod.getTypeName());
        return bucket != TYPE_UNKNOWN ? bucket : hintFromName(vod.getName());
    }

    // 片名类型提示：「仙逆真人版/古装版」→真人剧桶、「动态漫画」→动漫桶、「剧场版」→电影桶
    private static int hintFromName(String name) {
        String t = normalize(name);
        if (t.isEmpty()) return TYPE_UNKNOWN;
        if (t.contains("综艺") || t.contains("真人秀") || t.contains("脱口秀")) return TYPE_VARIETY;
        if (t.contains("真人") || t.contains("电视剧") || t.contains("网剧") || t.contains("古装") || t.contains("短剧")) return TYPE_DRAMA;
        if (t.contains("动画") || t.contains("动漫") || t.contains("番剧") || t.contains("动态漫画")) return TYPE_ANIME;
        if (t.contains("剧场版") || t.contains("电影版")) return TYPE_MOVIE;
        return TYPE_UNKNOWN;
    }

    private static int year(String text) {
        Matcher matcher = YEAR.matcher(text == null ? "" : text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static boolean disjoint(String a, String b, boolean area) {
        Set<String> setA = tokens(a, area);
        Set<String> setB = tokens(b, area);
        if (setA.isEmpty() || setB.isEmpty()) return false;
        for (String token : setA) if (setB.contains(token)) return false;
        return true;
    }

    private static Set<String> tokens(String text, boolean area) {
        Set<String> result = new HashSet<>();
        if (TextUtils.isEmpty(text)) return result;
        for (String raw : SPLIT.split(text)) {
            String token = normalize(raw);
            if (token.isEmpty() || VOID.contains(token)) continue;
            result.add(area ? normArea(token) : token);
        }
        return result;
    }

    private static String normArea(String token) {
        if (token.contains("大陆") || token.contains("内地") || token.equals("中国") || token.equals("中国大陆")) return "cn";
        if (token.contains("香港") || token.equals("港")) return "hk";
        if (token.contains("台湾") || token.equals("台")) return "tw";
        return token;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
