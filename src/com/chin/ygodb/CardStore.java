package com.chin.ygodb;

import java.util.ArrayList;
import java.util.Hashtable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.chin.common.Util;
import com.chin.ygodb.CardStore;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * A singleton class that acts as a storage for card information. Support lazy loading information.
 *
 * For now:
 * - the cardDOM is saved, so if you want the details from it you need to parse it manually
 *
 * @author Chin
 *
 */
public final class CardStore {

    public static class Pair {
        public String key;
        public String value;
        public Pair(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public enum CardAdditionalInfoType{
        Ruling, Tips, Trivia
    }

    public static Hashtable<String, String> columnNameMap = new Hashtable<String, String>();
    static {
        // not all columns are in here, just those in the info section
        columnNameMap.put("attribute"      , "Attribute");
        columnNameMap.put("types"          , "Types");
        columnNameMap.put("level"          , "Level");
        columnNameMap.put("atkdef"         , "ATK/DEF");
        columnNameMap.put("cardnum"        , "Card Number");
        columnNameMap.put("passcode"       , "Passcode");
        columnNameMap.put("effectTypes"    , "Card effect types");
        columnNameMap.put("materials"      , "Materials");
        columnNameMap.put("fusionMaterials", "Fusion Material");
        columnNameMap.put("rank"           , "Rank");
        columnNameMap.put("ritualSpell"    , "Ritual Spell Card required");
        columnNameMap.put("pendulumScale"  , "Pendulum Scale");
        columnNameMap.put("type"           , "Type");
        columnNameMap.put("property"       , "Property");
        columnNameMap.put("summonedBy"     , "Summoned by the effect of");
        columnNameMap.put("limitText"      , "Limitation Text");
        columnNameMap.put("synchroMaterial", "Synchro Material");
        columnNameMap.put("ritualMonster"  , "Ritual Monster required");
        columnNameMap.put("ocgStatus"      , "OCG");
        columnNameMap.put("tcgAdvStatus"   , "TCG Advanced");
        columnNameMap.put("tcgTrnStatus"   , "TCG Traditional");
    }

    // a list of all cards available, initialized in MainActivity's onCreate()
    public static ArrayList<String> cardList = null;

    // map a card name to its wiki page url, initialized in MainActivity's onCreate()
    public static Hashtable<String, String[]> cardLinkTable = null;

    // a storage for cards' detail after being fetched online
    private static Hashtable<String, Document> cardDomCache = new Hashtable<String, Document>();

    private static CardStore CARDSTORE;
    private static Context context;

    // flag: initialized the cardList, but not the cardLinkTable
    static boolean initializedOffline = false;

    static boolean initializedOnline = false;
    /**
     * Private constructor. For singleton.
     */
    private CardStore(Context context) {
        if (CARDSTORE != null) {
            throw new IllegalStateException("Already instantiated");
        }
        CardStore.context = context;
    }

    /**
     * Get the only instance of this class. Because of singleton.
     * @return The only instance of this class.
     */
    public static CardStore getInstance(Context context) {
        if (CARDSTORE == null) {
            CARDSTORE = new CardStore(context);
        }
        return CARDSTORE;
    }

    public void initializeCardList() throws Exception {
        if (initializedOnline) return;
        if (Util.hasNetworkConnectivity(context)) {
            Log.i("YGODB", "Initializing online...");
            cardList = new ArrayList<String>(8192);
            cardLinkTable = new Hashtable<String, String[]>(8192);
            initializeCardListOnline(null, true);
            initializeCardListOnline(null, false);
            initializedOnline = true;
            Log.i("YGODB", "Done initializing online.");
        }
        else if (!initializedOffline) {
            Log.i("YGODB", "Initializing offline...");
            CardStore.cardList = new ArrayList<String>(8192);
            initializeCardListOffline();
            initializedOffline = true;
            Log.i("YGODB", "Done initializing offline.");
        }
        Log.i("YGODB", "Number of cards: " + cardList.size());
    }

    private void initializeCardListOffline() {
        DatabaseQuerier dbq = new DatabaseQuerier(context);
        SQLiteDatabase db = dbq.getDatabase();
        Cursor cursor = db.rawQuery("select name from card", null);

      if (cursor.moveToFirst()) {
          while (cursor.isAfterLast() == false) {
              String name = cursor.getString(cursor.getColumnIndex("name"));
              cardList.add(name);
              cursor.moveToNext();
          }
      }
    }

    private void initializeCardListOnline(String offset, boolean isTcg) throws Exception {
        // this will return up to 5000 articles in the TCG_cards/OCG_cards category. Note that this is not always up-to-date,
        // as newly added articles may take a day or two before showing up in here
        String url;
        if (isTcg) {
            url = "http://yugioh.wikia.com/api/v1/Articles/List?category=TCG_cards&limit=5000&namespaces=0";
        }
        else {
            url = "http://yugioh.wikia.com/api/v1/Articles/List?category=OCG_cards&limit=5000&namespaces=0";
        }

        if (offset != null) {
            url = url + "&offset=" + offset;
        }
        String jsonString = Jsoup.connect(url).ignoreContentType(true).execute().body();

        JSONObject myJSON = new JSONObject(jsonString);
        JSONArray myArray = myJSON.getJSONArray("items");
        for (int i = 0; i < myArray.length(); i++) {
            String cardName = myArray.getJSONObject(i).getString("title");
            if (!cardLinkTable.containsKey(cardName)) {
                cardList.add(cardName);
                String[] tmp = {myArray.getJSONObject(i).getString("url"),
                                myArray.getJSONObject(i).getString("id")};
                cardLinkTable.put(cardName, tmp);
            }
        }

        if (myJSON.has("offset")) {
            initializeCardListOnline((String) myJSON.get("offset"), isTcg);
        }
    }

    public void getCardDomReady(String cardName) throws Exception {
        initializeCardList();
        if (!Util.hasNetworkConnectivity(context)) {
            return; // what else can we do? switch to offline db, meh
        }
        if (cardDomCache.containsKey(cardName)) {
            return; // already cached, just return
        }

        String cardURL = "http://yugioh.wikia.com" + CardStore.cardLinkTable.get(cardName)[0];

        String cardHTML = null;
        try {
            cardHTML = Jsoup.connect(cardURL).ignoreContentType(true).execute().body();
        } catch (Exception e) {
            Log.e("CardDetail", "Error fetching the card HTML page");
            e.printStackTrace();
        }
        Document cardDOM = Jsoup.parse(cardHTML);

        // save the DOM for later use. Should look into this so that it doesn't cause huge mem usage
        cardDomCache.put(cardName, cardDOM);
    }

    public Document getCardDom(String cardName) throws Exception {
        initializeCardList();
        getCardDomReady(cardName);
        return cardDomCache.get(cardName);
    }

    public String getImageLink(String cardName) throws Exception {
        getCardDomReady(cardName);
        Document dom = cardDomCache.get(cardName);

        Element td = dom.getElementsByClass("cardtable-cardimage").first();

        String imageUrl = td.getElementsByTag("a").first().attr("href");
        return imageUrl;
    }


    //////////////////////////////////////////////////////////////////////
    // CARD LORE
    //////////////////////////////////////////////////////////////////////

    public String getCardLore(String cardName) throws Exception {
        if (Util.hasNetworkConnectivity(context)) {
            return getCardLoreOnline(cardName);
        }
        else {
            return getCardLoreOffline(cardName);
        }
    }

    private String getCardLoreOffline(String cardName) {
        DatabaseQuerier dbq = new DatabaseQuerier(context);
        SQLiteDatabase db = dbq.getDatabase();
        Cursor cursor = db.rawQuery("select lore from card where name = ?", new String[] {cardName});

        // assuming we always have 1 result...
        cursor.moveToFirst();

        String lore = cursor.getString(cursor.getColumnIndex("lore"));
        return lore;
    }

    private String getCardLoreOnline(String cardName) throws Exception {
        initializeCardList();
        getCardDomReady(cardName);
        Document dom = cardDomCache.get(cardName);

        Element effectBox = dom.getElementsByClass("cardtablespanrow").first().getElementsByClass("navbox-list").first();
        String effect = YgoWikiaHtmlCleaner.getCleanedHtml(effectBox);

        // turn <dl> into <p> and <dt> into <b>
        effect = effect.replace("<dl", "<p").replace("dl>", "p>").replace("<dt", "<b").replace("dt>", "b>");
        return effect;
    }


    //////////////////////////////////////////////////////////////////////
    // CARD INFO
    //////////////////////////////////////////////////////////////////////

    public ArrayList<Pair> getCardInfo(String cardName) throws Exception {
        if (Util.hasNetworkConnectivity(context)) {
            return getCardInfoOnline(cardName);
        }
        else {
            return getCardInfoOffline(cardName);
        }
    }

    private ArrayList<Pair> getCardInfoOffline(String cardName) {
        ArrayList<Pair> array = new ArrayList<Pair>();
        DatabaseQuerier dbq = new DatabaseQuerier(context);
        SQLiteDatabase db = dbq.getDatabase();
        Cursor cursor = db.rawQuery("select * from card where name = ?", new String[] {cardName});

        // assuming we always have 1 result...
        cursor.moveToFirst();

        // order of the columns here is important, to make it persistent between online vs offline
        String[] columns = new String[] {"attribute", "types", "type", "property", "level", "rank", "pendulumScale",
                "atkdef", "cardnum", "passcode", "limitText", "ritualSpell", "ritualMonster", "fusionMaterials",
                "synchroMaterial", "materials", "summonedBy", "effectTypes"};

        for (int i = 0; i < columns.length; i++) {
            String value = cursor.getString(cursor.getColumnIndex(columns[i]));
            if (!value.equals("")) {
                array.add(new Pair(columnNameMap.get(columns[i]), value));
            }
        }
        return array;
    }

    private ArrayList<Pair> getCardInfoOnline(String cardName) throws Exception {
        initializeCardList();
        ArrayList<Pair> infos = new ArrayList<CardStore.Pair>();
        getCardDomReady(cardName);
        Document dom = cardDomCache.get(cardName);
        Elements rows = dom.getElementsByClass("cardtable").first().getElementsByClass("cardtablerow");

        // first row is "Attribute" for monster, "Type" for spell/trap and "Types" for token
        boolean foundFirstRow = false;
        for (Element row : rows) {
            Element header = row.getElementsByClass("cardtablerowheader").first();
            if (header == null) continue;
            String headerText = header.text();
            if (!foundFirstRow && !headerText.equals("Attribute") && !headerText.equals("Type") && !headerText.equals("Types")) {
                continue;
            }
            if (headerText.equals("Other card information") || header.equals("External links")) {
                // we have reached the end for some reasons, exit now
                break;
            }
            else {
                foundFirstRow = true;
                String data = row.getElementsByClass("cardtablerowdata").first().text();
                infos.add(new Pair(headerText, data));
                if (headerText.equals("Card effect types") || headerText.equals("Limitation Text")) {
                    break;
                }
            }
        }

        return infos;
    }

    //////////////////////////////////////////////////////////////////////
    // CARD STATUS
    //////////////////////////////////////////////////////////////////////

    public ArrayList<Pair> getCardStatus(String cardName) throws Exception {
        if (Util.hasNetworkConnectivity(context)) {
            return getCardStatusOnline(cardName);
        }
        else {
            return getCardStatusOffline(cardName);
        }
    }

    private ArrayList<Pair> getCardStatusOffline(String cardName) {
        ArrayList<Pair> array = new ArrayList<Pair>();
        DatabaseQuerier dbq = new DatabaseQuerier(context);
        SQLiteDatabase db = dbq.getDatabase();
        Cursor cursor = db.rawQuery("select ocgStatus, tcgAdvStatus, tcgTrnStatus from card where name = ?", new String[] {cardName});

        // assuming we always have 1 result...
        cursor.moveToFirst();

        // order of the columns here is important, to make it persistent between online vs offline
        String[] columns = new String[] {"ocgStatus", "tcgAdvStatus", "tcgTrnStatus"};

        for (int i = 0; i < columns.length; i++) {
            String value = cursor.getString(cursor.getColumnIndex(columns[i]));
            if (value.equals("")) {
                continue;
            }

            if (value.equals("U")) {
                value = "Unlimited";
            }

            array.add(new Pair(columnNameMap.get(columns[i]), value));
        }
        return array;
    }

    private ArrayList<Pair> getCardStatusOnline(String cardName) throws Exception {
        initializeCardList();
        ArrayList<Pair> statuses = new ArrayList<CardStore.Pair>();
        getCardDomReady(cardName);
        Document dom = cardDomCache.get(cardName);

        Elements statusRows = dom.getElementsByClass("cardtablestatuses").first().getElementsByTag("tr");
        Element statusRow = null;
        for (int i = 0; i < statusRows.size(); i++) {
            if (statusRows.get(i).text().equals("TCG/OCG statuses")) {
                statusRow = statusRows.get(i + 1);
                break;
            }
        }

        if (statusRow == null) {
            Log.i("YGODB", "Card banlist status not found online");
            return statuses;
        }

        Elements th = statusRow.getElementsByTag("th");
        Elements td = statusRow.getElementsByTag("td");

        for (int i = 0; i < th.size(); i++) {
            statuses.add(new Pair(th.get(i).text(), td.get(i).text()));
        }

        return statuses;
    }

    //////////////////////////////////////////////////////////////////////
    // CARD RULING, TIPS AND TRIVIA
    //////////////////////////////////////////////////////////////////////

    public String getCardRuling(String cardName) throws Exception {
        return getCardGenericInfo(CardAdditionalInfoType.Ruling, cardName);
    }

    public String getCardTips(String cardName) throws Exception {
        return getCardGenericInfo(CardAdditionalInfoType.Tips, cardName);
    }

    public String getCardTrivia(String cardName) throws Exception {
        return getCardGenericInfo(CardAdditionalInfoType.Trivia, cardName);
    }

    public String getCardGenericInfo(CardAdditionalInfoType type, String cardName) throws Exception {
        if (Util.hasNetworkConnectivity(context) && cardLinkTable != null) {
            return getCardGenericInfoOnline(type, cardName);
        }
        else {
            return getCardGenericInfoOffline(type, cardName);
        }
    }

    private String getCardGenericInfoOffline(CardAdditionalInfoType type, String cardName) throws Exception {
        DatabaseQuerier dbq = new DatabaseQuerier(context);
        SQLiteDatabase db = dbq.getDatabase();
        String columnName = "";
        switch (type) {
            case Ruling: columnName = "ruling"; break;
            case Tips:   columnName = "tips";   break;
            case Trivia: columnName = "trivia"; break;
            default:
                throw new Exception("Unknown type of additional info!");
        }
        Cursor cursor = db.rawQuery("select " + columnName + " from card where name = ?", new String[] {cardName});

        // assuming we always have 1 result...
        cursor.moveToFirst();

        String value = cursor.getString(cursor.getColumnIndex(columnName));
        if (value.equals("")) {
            value = "Not available.";
        }
        return value;
    }

    private String getCardGenericInfoOnline(CardAdditionalInfoType type, String cardName) throws Exception {
        initializeCardList();
        String baseUrl = "";
        switch (type) {
            case Ruling:
                baseUrl = "http://yugioh.wikia.com/wiki/Card_Rulings:";
                break;
            case Tips:
                baseUrl = "http://yugioh.wikia.com/wiki/Card_Tips:";
                break;
            case Trivia:
                baseUrl = "http://yugioh.wikia.com/wiki/Card_Trivia:";
                break;
            default:
                throw new Exception("Unknown type of additional info!");
        }
        String url = baseUrl + CardStore.cardLinkTable.get(cardName)[0].substring(6);

        Document dom = null;

        try {
            dom = Jsoup.parse(Jsoup.connect(url).ignoreContentType(true).execute().body());
        } catch (Exception e) {
            Log.i("YGODB", "Error fetching " + url);
            return "Not available.";
        }

        Element content = dom.getElementById("mw-content-text");
        String info = YgoWikiaHtmlCleaner.getCleanedHtml(content);
        return info;
    }
}

