package org.invik.bots;

import org.slf4j.LoggerFactory;
import twitter4j.*;
import twitter4j.auth.AccessToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Hello world!
 */
public class Unfollow {

    private static File DLED;
    private static Twitter twitter;
    private static Properties CONFIG;
    private final static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(Unfollow.class);
    private static String username;
    private static String accessToken;
    private static String accessTokenSecret;
    private static String consumerKey;
    private static String consumerSecret;
    private static Map<String, Integer> rateLimitStatusMap;
    private static Map<String, RateLimitStatus> rateLimitStatusMapOrig;


    public static void main(String[] args) throws Exception {
        loadConfig(args);
        //lecture du fichier de sauvegarde des telechargements
        // The factory instance is re-useable and thread safe.
        TwitterFactory factory = new TwitterFactory();
        AccessToken accessToken = loadAccessToken();
        twitter = factory.getInstance();
        twitter.setOAuthConsumer(Unfollow.consumerKey, Unfollow.consumerSecret);
        twitter.setOAuthAccessToken(accessToken);
        rateLimitStatusMap = new HashMap<>();

        rateLimitStatusMapOrig = twitter.getRateLimitStatus();
        for (final String key : rateLimitStatusMapOrig.keySet()) {
            Unfollow.rateLimitStatusMap.put(key, rateLimitStatusMapOrig.get(key).getRemaining());
        }

        for (String endPoint : rateLimitStatusMapOrig.keySet()) {
            RateLimitStatus rateLimitStatus = rateLimitStatusMapOrig.get(endPoint);
            LOGGER.trace("Enpoint: {}", endPoint);
            LOGGER.trace("Limit: {}", rateLimitStatus.getLimit());
            LOGGER.trace("Remaining: {}", rateLimitStatus.getRemaining());
            LOGGER.trace("ResetTimeInSeconds: {}", rateLimitStatus.getResetTimeInSeconds());
            LOGGER.trace("SecondsUntilReset: {}", rateLimitStatus.getSecondsUntilReset());
            LOGGER.trace("-------------------------------------------------------------------------------------------------");
        }

        IDs test = twitter.friendsFollowers().getFriendsIDs(username, -1L, 200);
        while (test.hasNext()) {
            for (final long user : test.getIDs()) {
                twitter.friendsFollowers().destroyFriendship(user);
                LOGGER.debug("Successfully unfollowed user {}", user);
            }
            LOGGER.debug("Loop finished, trying new one");
            test = twitter.friendsFollowers().getFriendsIDs(username, -1L, 200);
        }
        System.exit(0);
    }

    private static boolean loadConfig(String[] args) throws IOException, URISyntaxException {

        File conf = new File(args[0]);
        InputStream is = new FileInputStream(conf);
        Unfollow.CONFIG = new Properties();
        Unfollow.CONFIG.load(is);

        Unfollow.DLED = new File(Unfollow.CONFIG.getProperty("tweets.mostrecentfile"));
        if (Unfollow.DLED.exists() && Unfollow.DLED.isDirectory()) {
            LOGGER.error("La cible n'est pas un fichier, abandon...");
            return false;
        }

        Unfollow.username = Unfollow.CONFIG.getProperty("auth.username");
        Unfollow.consumerKey = Unfollow.CONFIG.getProperty("auth.consumerkey");
        Unfollow.consumerSecret = Unfollow.CONFIG.getProperty("auth.consumersecret");
        Unfollow.accessToken = Unfollow.CONFIG.getProperty("auth.accesstoken");
        Unfollow.accessTokenSecret = Unfollow.CONFIG.getProperty("auth.accesstokensecret");

        return true;
    }

    private static AccessToken loadAccessToken() {
        String token = Unfollow.accessToken; // load from a persistent store
        String tokenSecret = Unfollow.accessTokenSecret; // load from a persistent store
        return new AccessToken(token, tokenSecret);
    }
}