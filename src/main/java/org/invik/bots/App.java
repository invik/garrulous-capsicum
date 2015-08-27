package org.invik.bots;

import org.slf4j.LoggerFactory;
import twitter4j.*;
import twitter4j.auth.AccessToken;

import java.io.*;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hello world!
 */
public class App {

    private static Boolean ANALYZE = false;
    private static File DLED;
    private static Twitter twitter;
    private static Properties CONFIG;
    private static Pattern twitterUsernamePattern = Pattern.compile("@(\\w){1,15}");
    private static Long mostRecentId;
    private static Integer maxSearches = 0;
    private static List<String> blackList;
    private final static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(App.class);
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
        FileInputStream f;
        try {
            LOGGER.debug("Chargement du fichier de sauvegarde...");
            f = new FileInputStream(App.DLED);
            ObjectInputStream s = new ObjectInputStream(f);
            mostRecentId = (Long) s.readObject();
            s.close();
            LOGGER.debug("Chargement du fichier de sauvegarde ok! Starting at status id {}", mostRecentId);
        } catch (FileNotFoundException | EOFException e) {
            LOGGER.debug("Fichier de sauvegarde absent ou illisible, creation...");
            mostRecentId = 0L;
        }
        // The factory instance is re-useable and thread safe.
        TwitterFactory factory = new TwitterFactory();
        AccessToken accessToken = loadAccessToken();
        twitter = factory.getInstance();
        twitter.setOAuthConsumer(App.consumerKey, App.consumerSecret);
        twitter.setOAuthAccessToken(accessToken);
        rateLimitStatusMap = new HashMap<>();

        rateLimitStatusMapOrig = twitter.getRateLimitStatus();
        for (final String key : rateLimitStatusMapOrig.keySet()) {
            App.rateLimitStatusMap.put(key, rateLimitStatusMapOrig.get(key).getRemaining());
        }

        for (String endPoint : rateLimitStatusMapOrig.keySet()) {
            RateLimitStatus rateLimitStatus = rateLimitStatusMapOrig.get(endPoint);
            LOGGER.debug("Enpoint: {}", endPoint);
            LOGGER.debug("Limit: {}", rateLimitStatus.getLimit());
            LOGGER.debug("Remaining: {}", rateLimitStatus.getRemaining());
            LOGGER.debug("ResetTimeInSeconds: {}", rateLimitStatus.getResetTimeInSeconds());
            LOGGER.debug("SecondsUntilReset: {}", rateLimitStatus.getSecondsUntilReset());
        }

        App.waitForAvailability("/search/tweets");

        Query query = new Query("follow rt concours");
        RateLimitStatus rateLimitStatus = App.rateLimitStatusMapOrig.get("/search/tweets");
        int remaining = rateLimitStatus.getRemaining() > maxSearches ? maxSearches : rateLimitStatus.getRemaining();
        query.count(remaining).setResultType(Query.ResultType.mixed);
        if (mostRecentId != 0) {
            query.setSinceId(mostRecentId);
        }
        QueryResult result;
        do {
            result = twitter.search(query);
            List<Status> statuses = result.getTweets().stream().filter(status1 -> !status1.isRetweetedByMe() && !status1.isRetweet()).collect(Collectors.toList());
            statuses.forEach(status -> {
                try {
                    analyzeStatus(status);
                } catch (TwitterException | InterruptedException e) {
                    LOGGER.error("Error when analyzing status", e);
                }
            });
            remaining--;
        } while ((query = result.nextQuery()) != null && remaining > 0);
        saveId();
        System.exit(0);
    }

    private static AccessToken loadAccessToken() {
        String token = App.accessToken; // load from a persistent store
        String tokenSecret = App.accessTokenSecret; // load from a persistent store
        return new AccessToken(token, tokenSecret);
    }

    private static void analyzeStatus(Status status) throws TwitterException, InterruptedException {
        if (mostRecentId < status.getId()) {
            mostRecentId = status.getId();
        }
        Matcher matcher = twitterUsernamePattern.matcher(status.getText());
        List<String> usernamesToFollow = new ArrayList<>();

        while (matcher.find()) {
            usernamesToFollow.add(matcher.group());
        }
        if (!usernamesToFollow.contains("@" + status.getUser().getScreenName()) && !usernamesToFollow.isEmpty()) {
            return;
        }
        if (blackList.contains(status.getUser().getScreenName())) {
            return;
        }
        App.waitForAvailability("/statuses/retweets/:id");
        ResponseList<Status> statuses = twitter.getRetweets(status.getId());
        if (!statuses.parallelStream().filter(status1 -> status1.getUser().getScreenName().equals(username)).collect(Collectors.toList()).isEmpty()) {
            return;
        }
        LOGGER.debug("Status id: {}", status.getId());
        LOGGER.debug("Status retweet status: {}", status.getRetweetedStatus() == null ? "none" : status.getRetweetedStatus().getText());
        LOGGER.debug("Status url: https://twitter.com/{}/status/{}", status.getUser().getScreenName(), status.getId());
        LOGGER.debug("User: {} and status text is:\n{}", "@" + status.getUser().getScreenName(), status.getText());
        if (usernamesToFollow.isEmpty()) {
            usernamesToFollow.add(status.getUser().getScreenName());
        }
        LOGGER.debug("Looking for users {} {} {} {} {} {}", usernamesToFollow.toArray());
        List<User> responseList;
        if (usernamesToFollow.size() == 1) {
            responseList = new ArrayList<>();
            App.waitForAvailability("/users/show/:id");
            responseList.add(twitter.showUser(usernamesToFollow.get(0)));
        } else {
            App.waitForAvailability("/users/lookup");
            responseList = twitter.lookupUsers(usernamesToFollow.stream().map(user -> user.substring(1)).collect(Collectors.toList()).toArray(new String[usernamesToFollow.size()]));
        }
        if (ANALYZE) {
            twitter.retweetStatus(status.getId());
            twitter.createFavorite(status.getId());
            responseList.forEach(username -> {
                try {
                    twitter.createFriendship(username.getId());
                    LOGGER.debug("User followed: {}", username.getScreenName());
                } catch (TwitterException e) {
                    LOGGER.error("Error when creating friendship", e);
                }
            });
        }
    }

    /**
     * Methode de chargement de la configuration
     *
     * @param args arguments contenant le chemin du fichier de configuration
     * @return true si succès du chargement, false sinon
     * @throws IOException
     */
    private static boolean loadConfig(String[] args) throws IOException, URISyntaxException {

        File conf = new File(args[0]);
        InputStream is = new FileInputStream(conf);
        App.CONFIG = new Properties();
        App.CONFIG.load(is);

        App.DLED = new File(App.CONFIG.getProperty("tweets.mostrecentfile"));
        if (App.DLED.exists() && App.DLED.isDirectory()) {
            LOGGER.error("La cible n'est pas un fichier, abandon...");
            return false;
        }

        App.ANALYZE = Boolean.valueOf(App.CONFIG.getProperty("tweets.analyze"));
        App.maxSearches = Integer.valueOf(App.CONFIG.getProperty("tweets.maxSearches"));
        App.username = App.CONFIG.getProperty("auth.username");
        App.consumerKey = App.CONFIG.getProperty("auth.consumerkey");
        App.consumerSecret = App.CONFIG.getProperty("auth.consumersecret");
        App.accessToken = App.CONFIG.getProperty("auth.accesstoken");
        App.accessTokenSecret = App.CONFIG.getProperty("auth.accesstokensecret");
        App.blackList = Arrays.asList(App.CONFIG.getProperty("blacklist").split(";"));

        return true;
    }

    private static void saveId() {
        try {
            FileOutputStream f = new FileOutputStream(App.DLED);
            ObjectOutputStream s = new ObjectOutputStream(f);
            s.writeObject(App.mostRecentId);
            s.close();
        } catch (FileNotFoundException e) {
            LOGGER.error("Ficher de sauvegarde introuvable", e);
        } catch (IOException e) {
            LOGGER.error("Ecriture du fichier de sauvegarde impossible", e);
        }
    }

    private static void waitForAvailability(String endpoint) throws InterruptedException, TwitterException {
        RateLimitStatus rateLimitStatus = rateLimitStatusMapOrig.get(endpoint);
        int remaining = rateLimitStatus.getRemaining() < App.rateLimitStatusMap.get(endpoint) ? rateLimitStatus.getRemaining() : App.rateLimitStatusMap.get(endpoint);
        LOGGER.debug("Remaining tries for endpoint {} -> {}", endpoint, remaining);
        if (remaining == 0) {
            LOGGER.info("Waiting {} seconds because rate limit", rateLimitStatus.getSecondsUntilReset() + 10);
            Thread.sleep((rateLimitStatus.getSecondsUntilReset() + 10) * 1000);
            App.rateLimitStatusMapOrig = twitter.getRateLimitStatus();
            for (final String key : rateLimitStatusMapOrig.keySet()) {
                App.rateLimitStatusMap.put(key, rateLimitStatusMapOrig.get(key).getRemaining());
            }
        }
        App.rateLimitStatusMap.put(endpoint, App.rateLimitStatusMap.get(endpoint) - 1);
    }
}
