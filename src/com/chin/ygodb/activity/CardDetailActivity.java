package com.chin.ygodb.activity;

import com.chin.ygodb.CardStore;
import com.chin.ygodb.CardStore.CardAdditionalInfoType;
import com.chin.ygodb.PagerSlidingTabStrip;
import com.chin.ygodb.asyncTask.AddCardInfoTask;
import com.chin.ygodb2.R;
import com.chin.common.MyTagHandler;

import android.os.AsyncTask;
import android.os.Bundle;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.text.Html;

/**
 * Activity to show all details about a card
 */
public class CardDetailActivity extends BaseFragmentActivity {

    public String cardName = null;

    private PagerSlidingTabStrip tabs;
    private ViewPager pager;
    private MyPagerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            cardName = savedInstanceState.getString("CARDNAME");
        }
        else {
            Intent intent = getIntent(); // careful, this intent may not be the intent from MainActivity...
            String tmpName = intent.getStringExtra(MainActivity.CARD_NAME);
            if (tmpName != null) {
                cardName = tmpName; // needed since we may come back from other activity, not just the main one
            }
        }

        tabs = (PagerSlidingTabStrip) findViewById(R.id.tabs);
        pager = (ViewPager) findViewById(R.id.pager);
        adapter = new MyPagerAdapter(getSupportFragmentManager());

        pager.setAdapter(adapter);

        final int pageMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        pager.setPageMargin(pageMargin);
        tabs.setViewPager(pager);
        tabs.setIndicatorColor(getResources().getColor(R.color.red));

        getActionBar().setTitle(cardName);
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        bundle.putString("CARDNAME", cardName);
    }

    /**
     * Fragment for the card info view
     */
    public static class CardInfoFragment extends Fragment {

        AsyncTask<?, ?, ?> myTask = null;
        static String cardName;

        public CardInfoFragment(String cardName) {
            CardInfoFragment.cardName = cardName;
        }

        public CardInfoFragment() {
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            // Inflate the layout for this fragment
            View view = inflater.inflate(R.layout.fragment_card_info, container, false);
            myTask = new AddCardInfoTask((CardDetailActivity) getActivity()).execute(cardName);
            return view;
        }

        @Override
        public void onPause() {
            super.onPause();
            if (myTask != null) {
                myTask.cancel(true);
                myTask = null;
            }
        }
    }

    public static class CardGenericDetailFragment extends Fragment {
        PopulateRulingAsyncTask myTask;

        private static final String TYPE = "TYPE";
        private static final String CARD_NAME = "CARD_NAME";

        CardAdditionalInfoType type;
        String cardName;

        public static CardGenericDetailFragment newInstance(CardAdditionalInfoType type, String cardName) {
            CardGenericDetailFragment f = new CardGenericDetailFragment();
            Bundle b = new Bundle();
            b.putSerializable(TYPE, type);
            b.putString(CARD_NAME, cardName);
            f.setArguments(b);
            return f;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            cardName = getArguments().getString(CARD_NAME);
            type = (CardAdditionalInfoType) getArguments().getSerializable(TYPE);
            setRetainInstance(true);
        }

        @SuppressLint("RtlHardcoded")
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_general_linear, container, false);
            LinearLayout layout = (LinearLayout) view.findViewById(R.id.fragment_layout);
            layout.setGravity(Gravity.RIGHT);

            myTask = (PopulateRulingAsyncTask) new PopulateRulingAsyncTask(layout, (CardDetailActivity) getActivity(), type, cardName).execute();

            return view;
        }

        @Override
        public void onPause() {
            super.onPause();
            if (myTask != null) {
                myTask.cancel(true);
                myTask = null;
            }
        }
    }

    public static class PopulateRulingAsyncTask extends AsyncTask<String, Void, String> {
        LinearLayout layout;
        CardDetailActivity activity;
        CardAdditionalInfoType type;
        String cardName;
        boolean exceptionOccurred = false;

        public PopulateRulingAsyncTask(LinearLayout layout, CardDetailActivity activity, CardAdditionalInfoType type, String cardName) {
            this.layout = layout;
            this.activity = activity;
            this.type = type;
            this.cardName = cardName;
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                String info = CardStore.getInstance(activity).getCardGenericInfo(type, cardName);
                return info;
            } catch (Exception e) {
                e.printStackTrace();

                // set the flag so we can do something about this in onPostExecute()
                exceptionOccurred = true;
            }
            return null;
        }

        @Override
        protected void onPostExecute(String param) {

            if (exceptionOccurred) {
                // remove the spinner
                ProgressBar pgrBar = (ProgressBar) activity.findViewById(R.id.progressBar_fragment_general);
                layout.removeView(pgrBar);
                TextView tv = new TextView(activity);
                layout.addView(tv);
                tv.setText("Not available");
                return;
            }

            TextView tv = new TextView(activity);
            layout.addView(tv);
            tv.setText(Html.fromHtml(param, null, new MyTagHandler()));

            // remove the spinner
            ProgressBar pgrBar = (ProgressBar) activity.findViewById(R.id.progressBar_fragment_general);
            layout.removeView(pgrBar);
        }
    }

    public class MyPagerAdapter extends FragmentPagerAdapter {

        private final String[] TITLES = { "Detail", "Ruling", "Tips", "Trivia"};

        public MyPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return TITLES[position];
        }

        @Override
        public int getCount() {
            return TITLES.length;
        }

        @Override
        public Fragment getItem(int position) {
            if (position == 0) {
                return new CardInfoFragment(cardName);
            }
            else if (position == 1){
                return CardGenericDetailFragment.newInstance(CardAdditionalInfoType.Ruling, cardName);
            }
            else if (position == 2) {
                return CardGenericDetailFragment.newInstance(CardAdditionalInfoType.Tips, cardName);
            }
            else {
                return CardGenericDetailFragment.newInstance(CardAdditionalInfoType.Trivia, cardName);
            }
        }
    }
}
