package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 1-5 star shop ratings. Ratings belong to a (shop, owner) pair, so whoever rents a shop next
 * starts with a clean slate instead of inheriting the previous renter's reputation.
 */
public class RatingManager {
    public record Summary(double average, int count) {
        public static final Summary NONE = new Summary(0, 0);
    }

    // Bayesian prior for sorting: a single 5-star vote shouldn't outrank a proven 4.8 shop.
    private static final double PRIOR_WEIGHT = 3.0;
    private static final double PRIOR_MEAN = 3.5;

    private final VillagerMerchants plugin;
    private final DatabaseManager db;
    /** "shopId|ownerId" -> {sum of stars, number of ratings}. */
    private final Map<String, double[]> agg = new HashMap<>();
    /** player -> the shop they bought from last (lets "/vm rate 5" work without a shop id). */
    private final Map<UUID, UUID> lastPurchased = new HashMap<>();
    /** "playerId|shopId" -> when we last asked them to rate. */
    private final Map<String, Long> promptedAt = new HashMap<>();

    public RatingManager(VillagerMerchants plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private static String key(String shopId, String owner) { return shopId + "|" + owner; }
    private static String key(Shop s) { return key(s.getId().toString(), s.getOwner().toString()); }

    public boolean enabled() { return cfg().getBoolean("ratings.enabled", true); }

    public void load() {
        for (DatabaseManager.RatingAgg a : db.loadRatingAggs()) {
            agg.put(key(a.shopId(), a.owner()), new double[]{a.sum(), a.count()});
        }
    }

    // ------------------------------------------------------------ reading

    public Summary summary(Shop s) {
        if (!enabled()) return Summary.NONE;
        double[] a = agg.get(key(s));
        if (a == null || a[1] <= 0) return Summary.NONE;
        return new Summary(a[0] / a[1], (int) a[1]);
    }

    /** Ranking score for the directory's "Top rated" sort (not what's displayed). */
    public double score(Shop s) {
        double[] a = agg.get(key(s));
        double sum = a == null ? 0 : a[0];
        double n = a == null ? 0 : a[1];
        return (sum + PRIOR_WEIGHT * PRIOR_MEAN) / (n + PRIOR_WEIGHT);
    }

    /** Text appended to the NPC name tag, e.g. " <yellow>(star)4.6". Empty when disabled or unrated. */
    public String nametagSuffix(Shop s) {
        if (!cfg().getBoolean("ratings.show-on-nametag", true)) return "";
        Summary r = summary(s);
        if (r.count() == 0) return "";
        return " <yellow>\u2605" + Text.oneDecimal(r.average());
    }

    public UUID lastPurchased(UUID player) { return lastPurchased.get(player); }

    public void recordPurchase(Player p, Shop shop) { lastPurchased.put(p.getUniqueId(), shop.getId()); }

    // ------------------------------------------------------------ prompting

    /** After a customer bought something: offer clickable stars (once in a while, and only if they haven't rated). */
    public void maybePrompt(Player p, Shop shop) {
        if (!enabled() || !cfg().getBoolean("ratings.prompt-after-purchase", true)) return;
        if (shop.isOwner(p.getUniqueId())) return;

        String pk = p.getUniqueId() + "|" + shop.getId();
        long now = System.currentTimeMillis();
        long cooldown = cfg().getLong("ratings.prompt-cooldown-minutes", 30) * 60_000L;
        Long last = promptedAt.get(pk);
        if (last != null && now - last < cooldown) return;
        promptedAt.put(pk, now);

        final String sid = shop.getId().toString();
        final String owner = shop.getOwner().toString();
        final String rater = p.getUniqueId().toString();
        final String ownerName = plugin.getShopManager().ownerName(shop.getOwner());
        db.async(() -> {
            if (db.getRating(sid, owner, rater) > 0) return; // already rated: don't nag
            plugin.sync(() -> {
                if (!p.isOnline()) return;
                StringBuilder sb = new StringBuilder("<gold>Enjoyed shopping at <white><owner>'s</white> shop? Rate it: ");
                for (int n = 1; n <= 5; n++) {
                    sb.append("<click:run_command:'/vm rate ").append(sid).append(' ').append(n).append("'>")
                      .append("<hover:show_text:'Rate ").append(n).append(" out of 5'>")
                      .append("<yellow>").append("\u2605".repeat(n)).append("</yellow></hover></click> ");
                }
                p.sendMessage(Text.mm(sb.toString(), Placeholder.unparsed("owner", ownerName)));
            });
        });
    }

    // ------------------------------------------------------------ rating

    public void rate(Player p, UUID shopId, int stars) {
        if (!enabled()) { p.sendMessage(Text.mm("<red>Ratings are disabled on this server.")); return; }
        if (stars < 1 || stars > 5) { p.sendMessage(Text.mm("<red>Pick between 1 and 5 stars.")); return; }
        Shop shop = plugin.getShopManager().getShop(shopId);
        if (shop == null || !shop.isActive(System.currentTimeMillis())) {
            p.sendMessage(Text.mm("<red>That shop isn't open any more."));
            return;
        }
        if (shop.isOwner(p.getUniqueId())) {
            p.sendMessage(Text.mm("<red>You can't rate your own shop."));
            return;
        }

        final boolean requirePurchase = cfg().getBoolean("ratings.require-purchase", true);
        final String sid = shop.getId().toString();
        final String owner = shop.getOwner().toString();
        final String rater = p.getUniqueId().toString();
        final UUID ownerId = shop.getOwner();
        final String raterName = p.getName();

        db.async(() -> {
            if (requirePurchase && !db.hasPurchased(sid, owner, rater)) {
                plugin.sync(() -> p.sendMessage(Text.mm("<red>Buy something from this shop before rating it.")));
                return;
            }
            int previous = db.getRating(sid, owner, rater);
            db.upsertRating(sid, owner, rater, stars, System.currentTimeMillis());
            DatabaseManager.RatingAgg a = db.ratingAgg(sid, owner);
            plugin.sync(() -> {
                agg.put(key(sid, owner), new double[]{a.sum(), a.count()});
                if (p.isOnline()) {
                    p.sendMessage(Text.mm(previous > 0
                            ? "<green>Updated your rating to <yellow><stars>\u2605</yellow>."
                            : "<green>Thanks for rating! You gave <yellow><stars>\u2605</yellow>.",
                            Placeholder.unparsed("stars", String.valueOf(stars))));
                }
                Player op = Bukkit.getPlayer(ownerId);
                if (op != null) {
                    op.sendMessage(Text.mm("<yellow><who></yellow> rated your shop <yellow><stars>\u2605</yellow> <gray>(avg <avg>, <count> rating(s))",
                            Placeholder.unparsed("who", raterName),
                            Placeholder.unparsed("stars", String.valueOf(stars)),
                            Placeholder.unparsed("avg", Text.oneDecimal(a.count() == 0 ? 0 : a.sum() / a.count())),
                            Placeholder.unparsed("count", String.valueOf(a.count()))));
                }
                plugin.getShopManager().refreshNameTag(shopId);
            });
        });
    }
}
