package it.niedermann.owncloud.notes.android.activity;

import android.app.SearchManager;
import android.content.Intent;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.exceptions.AccountImportCancelledException;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NextcloudHttpRequestFailedException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.accountswitcher.AccountSwitcherDialog;
import it.niedermann.owncloud.notes.accountswitcher.AccountSwitcherListener;
import it.niedermann.owncloud.notes.android.MultiSelectedActionModeCallback;
import it.niedermann.owncloud.notes.android.NotesListViewItemTouchHelper;
import it.niedermann.owncloud.notes.android.fragment.AccountChooserAdapter.MoveAccountListener;
import it.niedermann.owncloud.notes.android.fragment.ExceptionDialogFragment;
import it.niedermann.owncloud.notes.branding.BrandedSnackbar;
import it.niedermann.owncloud.notes.branding.BrandingUtil;
import it.niedermann.owncloud.notes.databinding.ActivityNotesListViewBinding;
import it.niedermann.owncloud.notes.databinding.DrawerLayoutBinding;
import it.niedermann.owncloud.notes.model.Capabilities;
import it.niedermann.owncloud.notes.model.Category;
import it.niedermann.owncloud.notes.model.DBNote;
import it.niedermann.owncloud.notes.model.ISyncCallback;
import it.niedermann.owncloud.notes.model.Item;
import it.niedermann.owncloud.notes.model.ItemAdapter;
import it.niedermann.owncloud.notes.model.LocalAccount;
import it.niedermann.owncloud.notes.model.NavigationAdapter;
import it.niedermann.owncloud.notes.model.NavigationAdapter.CategoryNavigationItem;
import it.niedermann.owncloud.notes.model.NavigationAdapter.NavigationItem;
import it.niedermann.owncloud.notes.model.NoteClickListener;
import it.niedermann.owncloud.notes.persistence.CapabilitiesClient;
import it.niedermann.owncloud.notes.persistence.CapabilitiesWorker;
import it.niedermann.owncloud.notes.persistence.LoadNotesListTask;
import it.niedermann.owncloud.notes.persistence.LoadNotesListTask.NotesLoadedListener;
import it.niedermann.owncloud.notes.persistence.NoteServerSyncHelper;
import it.niedermann.owncloud.notes.persistence.NoteServerSyncHelper.ViewProvider;
import it.niedermann.owncloud.notes.persistence.NotesDatabase;
import it.niedermann.owncloud.notes.util.NoteUtil;

import static it.niedermann.owncloud.notes.util.ColorUtil.contrastRatioIsSufficient;
import static it.niedermann.owncloud.notes.util.SSOUtil.askForNewAccount;

public class NotesListViewActivity extends LockedActivity implements NoteClickListener, ViewProvider, MoveAccountListener, AccountSwitcherListener {

    private static final String TAG = NotesListViewActivity.class.getSimpleName();

    public static final String CREATED_NOTE = "it.niedermann.owncloud.notes.created_notes";
    public static final String ADAPTER_KEY_RECENT = "recent";
    public static final String ADAPTER_KEY_STARRED = "starred";
    public static final String ACTION_FAVORITES = "it.niedermann.owncloud.notes.favorites";
    public static final String ACTION_RECENT = "it.niedermann.owncloud.notes.recent";

    private static final String SAVED_STATE_NAVIGATION_SELECTION = "navigationSelection";
    private static final String SAVED_STATE_NAVIGATION_ADAPTER_SLECTION = "navigationAdapterSelection";
    private static final String SAVED_STATE_NAVIGATION_OPEN = "navigationOpen";

    private final static int create_note_cmd = 0;
    private final static int show_single_note_cmd = 1;
    private final static int server_settings = 2;
    private final static int about = 3;

    /**
     * Used to detect the onResume() call after the import dialog has been displayed.
     * https://github.com/stefan-niedermann/nextcloud-notes/pull/599/commits/f40eab402d122f113020200751894fa39c8b9fcc#r334239634
     */
    private boolean notAuthorizedAccountHandled = false;

    protected SingleSignOnAccount ssoAccount;
    protected LocalAccount localAccount;

    protected DrawerLayoutBinding binding;
    protected ActivityNotesListViewBinding activityBinding;

    private CoordinatorLayout coordinatorLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    protected FloatingActionButton fabCreate;
    private RecyclerView listView;

    protected ItemAdapter adapter = null;

    protected NotesDatabase db = null;
    private NavigationAdapter adapterCategories;
    private NavigationItem itemRecent;
    private NavigationItem itemFavorites;
    private NavigationItem itemUncategorized;
    private Category navigationSelection = new Category(null, null);
    private String navigationOpen = "";
    private ActionMode mActionMode;
    private SearchView searchView = null;
    private final ISyncCallback syncCallBack = () -> {
        adapter.clearSelection(listView);
        if (mActionMode != null) {
            mActionMode.finish();
        }
        refreshLists();
        swipeRefreshLayout.setRefreshing(false);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CapabilitiesWorker.update(this);
        binding = DrawerLayoutBinding.inflate(getLayoutInflater());
        activityBinding = ActivityNotesListViewBinding.bind(binding.activityNotesListView.getRoot());

        setContentView(binding.getRoot());

        this.coordinatorLayout = binding.activityNotesListView.activityNotesListView;
        this.swipeRefreshLayout = binding.activityNotesListView.swiperefreshlayout;
        this.fabCreate = binding.activityNotesListView.fabCreate;
        this.listView = binding.activityNotesListView.recyclerView;

        String categoryAdapterSelectedItem = ADAPTER_KEY_RECENT;
        if (savedInstanceState == null) {
            if (ACTION_RECENT.equals(getIntent().getAction())) {
                categoryAdapterSelectedItem = ADAPTER_KEY_RECENT;
            } else if (ACTION_FAVORITES.equals(getIntent().getAction())) {
                categoryAdapterSelectedItem = ADAPTER_KEY_STARRED;
                navigationSelection = new Category(null, true);
            }
        } else {
            navigationSelection = (Category) savedInstanceState.getSerializable(SAVED_STATE_NAVIGATION_SELECTION);
            navigationOpen = savedInstanceState.getString(SAVED_STATE_NAVIGATION_OPEN);
            categoryAdapterSelectedItem = savedInstanceState.getString(SAVED_STATE_NAVIGATION_ADAPTER_SLECTION);
        }

        db = NotesDatabase.getInstance(this);

        setupActionBar();
        setupNavigationList(categoryAdapterSelectedItem);
        setupNavigationMenu();
        setupNotesList();
    }

    @Override
    protected void onResume() {
        try {
            ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(getApplicationContext());
            if (localAccount == null || !localAccount.getAccountName().equals(ssoAccount.name)) {
                selectAccount(ssoAccount.name);
            }
        } catch (NoCurrentAccountSelectedException | NextcloudFilesAppAccountNotFoundException e) {
            if (localAccount == null) {
                List<LocalAccount> localAccounts = db.getAccounts();
                if (localAccounts.size() > 0) {
                    localAccount = localAccounts.get(0);
                }
            }
            if (!notAuthorizedAccountHandled) {
                handleNotAuthorizedAccount();
            }
        }

        // refresh and sync every time the activity gets
        refreshLists();
        if (localAccount != null) {
            synchronize();
            db.getNoteServerSyncHelper().addCallbackPull(ssoAccount, syncCallBack);
        }
        super.onResume();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (localAccount != null) {
            outState.putSerializable(SAVED_STATE_NAVIGATION_SELECTION, navigationSelection);
            outState.putString(SAVED_STATE_NAVIGATION_ADAPTER_SLECTION, adapterCategories.getSelectedItem());
            outState.putString(SAVED_STATE_NAVIGATION_OPEN, navigationOpen);
        }
    }

    private void selectAccount(String accountName) {
        fabCreate.hide();
        SingleAccountHelper.setCurrentAccount(getApplicationContext(), accountName);
        localAccount = db.getLocalAccountByAccountName(accountName);
        if (localAccount != null) {
            try {
                BrandingUtil.saveBrandColors(this, localAccount.getColor(), localAccount.getTextColor());
                ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(getApplicationContext());
                new NotesListViewItemTouchHelper(ssoAccount, this, db, adapter, syncCallBack, this::refreshLists, swipeRefreshLayout, this).attachToRecyclerView(listView);
                synchronize();
            } catch (NextcloudFilesAppAccountNotFoundException | NoCurrentAccountSelectedException e) {
                Log.i(TAG, "Tried to select account, but got an " + e.getClass().getSimpleName() + ". Asking for importing an account...");
                handleNotAuthorizedAccount();
            }
            refreshLists();
            fabCreate.show();
            activityBinding.launchAccountSwitcher.setOnClickListener((v) -> AccountSwitcherDialog.newInstance(localAccount.getId()).show(getSupportFragmentManager(), AccountSwitcherDialog.class.getSimpleName()));
            setupNavigationList(ADAPTER_KEY_RECENT);
            updateCurrentAccountAvatar();
        } else {
            if (!notAuthorizedAccountHandled) {
                handleNotAuthorizedAccount();
            }
        }
    }

    private void handleNotAuthorizedAccount() {
        fabCreate.hide();
        swipeRefreshLayout.setRefreshing(false);
        askForNewAccount(this);
        notAuthorizedAccountHandled = true;
    }

    private void setupActionBar() {
        Toolbar toolbar = binding.activityNotesListView.toolbar;
        setSupportActionBar(toolbar);

        activityBinding.homeToolbar.setOnClickListener((v) -> {
            if (searchView == null || searchView.isIconified()) {
                activityBinding.homeToolbar.setVisibility(View.GONE);
                activityBinding.toolbar.setVisibility(View.VISIBLE);
                searchView.setIconified(false);
                ViewCompat.setElevation(activityBinding.appBar, getResources().getDimension(R.dimen.design_appbar_elevation));
            }
        });
        activityBinding.menuButton.setOnClickListener((v) -> binding.drawerLayout.openDrawer(GravityCompat.START));
    }

    private void setupNotesList() {
        initList();

        ((RecyclerView) findViewById(R.id.recycler_view)).addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0)
                    fabCreate.hide();
                else if (dy < 0)
                    fabCreate.show();
            }
        });

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (ssoAccount == null) {
                swipeRefreshLayout.setRefreshing(false);
                askForNewAccount(this);
            } else {
                Log.i(TAG, "Clearing Glide memory cache");
                Glide.get(this).clearMemory();
                new Thread(() -> {
                    Log.i(TAG, "Clearing Glide disk cache");
                    Glide.get(getApplicationContext()).clearDiskCache();
                }).start();
                new Thread(() -> {
                    Log.i(TAG, "Refreshing capabilities for " + ssoAccount.name);
                    final Capabilities capabilities;
                    try {
                        capabilities = CapabilitiesClient.getCapabilities(getApplicationContext(), ssoAccount, localAccount.getCapabilitiesETag());
                        db.updateCapabilitiesETag(localAccount.getId(), capabilities.getETag());
                        db.updateBrand(localAccount.getId(), capabilities);
                        db.updateBrand(localAccount.getId(), capabilities);
                        localAccount.setColor(Color.parseColor(capabilities.getColor()));
                        localAccount.setTextColor(Color.parseColor(capabilities.getTextColor()));
                        BrandingUtil.saveBrandColors(this, localAccount.getColor(), localAccount.getTextColor());
                        db.updateApiVersion(localAccount.getId(), capabilities.getApiVersion());
                        Log.i(TAG, capabilities.toString());
                    } catch (Exception e) {
                        if (e instanceof NextcloudHttpRequestFailedException && ((NextcloudHttpRequestFailedException) e).getStatusCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            Log.i(TAG, "Capabilities not modified.");
                        } else {
                            e.printStackTrace();
                        }
                    } finally {
                        // Even if the capabilities endpoint makes trouble, we can still try to synchronize the notes
                        synchronize();
                    }
                }).start();
            }
        });

        // Floating Action Button
        fabCreate.setOnClickListener((View view) -> {
            Intent createIntent = new Intent(getApplicationContext(), EditNoteActivity.class);
            createIntent.putExtra(EditNoteActivity.PARAM_CATEGORY, navigationSelection);
            if (searchView != null && !searchView.isIconified() && searchView.getQuery().length() > 0) {
                createIntent.putExtra(EditNoteActivity.PARAM_CONTENT, searchView.getQuery().toString());
                invalidateOptionsMenu();
            }
            startActivityForResult(createIntent, create_note_cmd);
        });
    }

    private void setupNavigationList(final String selectedItem) {
        itemRecent = new NavigationItem(ADAPTER_KEY_RECENT, getString(R.string.label_all_notes), null, R.drawable.ic_access_time_grey600_24dp);
        itemFavorites = new NavigationItem(ADAPTER_KEY_STARRED, getString(R.string.label_favorites), null, R.drawable.ic_star_yellow_24dp);
        adapterCategories = new NavigationAdapter(this, new NavigationAdapter.ClickListener() {
            @Override
            public void onItemClick(NavigationItem item) {
                selectItem(item, true);
            }

            private void selectItem(NavigationItem item, boolean closeNavigation) {
                adapterCategories.setSelectedItem(item.id);

                // update current selection
                if (itemRecent.equals(item)) {
                    navigationSelection = new Category(null, null);
                } else if (itemFavorites.equals(item)) {
                    navigationSelection = new Category(null, true);
                } else if (itemUncategorized != null && itemUncategorized.equals(item)) {
                    navigationSelection = new Category("", null);
                } else {
                    navigationSelection = new Category(item.label, null);
                }

                // auto-close sub-folder in Navigation if selection is outside of that folder
                if (navigationOpen != null) {
                    int slashIndex = navigationSelection.category == null ? -1 : navigationSelection.category.indexOf('/');
                    String rootCategory = slashIndex < 0 ? navigationSelection.category : navigationSelection.category.substring(0, slashIndex);
                    if (!navigationOpen.equals(rootCategory)) {
                        navigationOpen = null;
                    }
                }

                // update views
                if (closeNavigation) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                }
                refreshLists(true);
            }

            @Override
            public void onIconClick(NavigationItem item) {
                if (item.icon == NavigationAdapter.ICON_MULTIPLE && !item.label.equals(navigationOpen)) {
                    navigationOpen = item.label;
                    selectItem(item, false);
                } else if (item.icon == NavigationAdapter.ICON_MULTIPLE || item.icon == NavigationAdapter.ICON_MULTIPLE_OPEN && item.label.equals(navigationOpen)) {
                    navigationOpen = null;
                    refreshLists();
                } else {
                    onItemClick(item);
                }
            }
        });
        adapterCategories.setSelectedItem(selectedItem);
        binding.navigationList.setAdapter(adapterCategories);
    }

    @Override
    public CoordinatorLayout getView() {
        return this.coordinatorLayout;
    }

    @Override
    public void applyBrand(int mainColor, int textColor) {
        applyBrandToPrimaryToolbar(activityBinding.toolbar);
        applyBrandToFAB(mainColor, textColor, activityBinding.fabCreate);

        binding.headerView.setBackgroundColor(mainColor);
        binding.appName.setTextColor(textColor);

        // TODO We assume, that the background of the spinner is always white
        activityBinding.swiperefreshlayout.setColorSchemeColors(contrastRatioIsSufficient(Color.WHITE, mainColor) ? mainColor : Color.BLACK);
        binding.appName.setTextColor(textColor);

        adapter.applyBrand(mainColor, textColor);
        adapterCategories.applyBrand(mainColor, textColor);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (searchView != null && !searchView.isIconified()) {
            activityBinding.homeToolbar.setVisibility(View.VISIBLE);
            activityBinding.toolbar.setVisibility(View.GONE);
            searchView.setIconified(true);
            ViewCompat.setElevation(activityBinding.appBar, 0);
            return true;
        } else {
            return super.onSupportNavigateUp();
        }
    }

    private class LoadCategoryListTask extends AsyncTask<Void, Void, List<NavigationItem>> {
        @Override
        protected List<NavigationItem> doInBackground(Void... voids) {
            if (localAccount == null) {
                return new ArrayList<>();
            }
            List<CategoryNavigationItem> categories = db.getCategories(localAccount.getId());
            if (!categories.isEmpty() && categories.get(0).label.isEmpty()) {
                itemUncategorized = categories.get(0);
                itemUncategorized.label = getString(R.string.action_uncategorized);
                itemUncategorized.icon = NavigationAdapter.ICON_NOFOLDER;
            } else {
                itemUncategorized = null;
            }

            Map<String, Integer> favorites = db.getFavoritesCount(localAccount.getId());
            //noinspection ConstantConditions
            int numFavorites = favorites.containsKey("1") ? favorites.get("1") : 0;
            //noinspection ConstantConditions
            int numNonFavorites = favorites.containsKey("0") ? favorites.get("0") : 0;
            itemFavorites.count = numFavorites;
            itemRecent.count = numFavorites + numNonFavorites;

            ArrayList<NavigationItem> items = new ArrayList<>();
            items.add(itemRecent);
            items.add(itemFavorites);
            NavigationItem lastPrimaryCategory = null;
            NavigationItem lastSecondaryCategory = null;
            for (NavigationItem item : categories) {
                int slashIndex = item.label.indexOf('/');
                String currentPrimaryCategory = slashIndex < 0 ? item.label : item.label.substring(0, slashIndex);
                String currentSecondaryCategory = null;
                boolean isCategoryOpen = currentPrimaryCategory.equals(navigationOpen);

                if (isCategoryOpen && !currentPrimaryCategory.equals(item.label)) {
                    String currentCategorySuffix = item.label.substring(navigationOpen.length() + 1);
                    int subSlashIndex = currentCategorySuffix.indexOf('/');
                    currentSecondaryCategory = subSlashIndex < 0 ? currentCategorySuffix : currentCategorySuffix.substring(0, subSlashIndex);
                }

                boolean belongsToLastPrimaryCategory = lastPrimaryCategory != null && currentPrimaryCategory.equals(lastPrimaryCategory.label);
                boolean belongsToLastSecondaryCategory = belongsToLastPrimaryCategory && lastSecondaryCategory != null && lastSecondaryCategory.label.equals(currentPrimaryCategory + "/" + currentSecondaryCategory);

                if (isCategoryOpen && !belongsToLastPrimaryCategory && currentSecondaryCategory != null) {
                    lastPrimaryCategory = new NavigationItem("category:" + currentPrimaryCategory, currentPrimaryCategory, 0, NavigationAdapter.ICON_MULTIPLE_OPEN);
                    items.add(lastPrimaryCategory);
                    belongsToLastPrimaryCategory = true;
                }

                if (belongsToLastPrimaryCategory && belongsToLastSecondaryCategory) {
                    lastSecondaryCategory.count += item.count;
                    lastSecondaryCategory.icon = NavigationAdapter.ICON_SUB_MULTIPLE;
                } else if (belongsToLastPrimaryCategory) {
                    if (isCategoryOpen) {
                        item.label = currentPrimaryCategory + "/" + currentSecondaryCategory;
                        item.id = "category:" + item.label;
                        item.icon = NavigationAdapter.ICON_SUB_FOLDER;
                        items.add(item);
                        lastSecondaryCategory = item;
                    } else {
                        lastPrimaryCategory.count += item.count;
                        lastPrimaryCategory.icon = NavigationAdapter.ICON_MULTIPLE;
                        lastSecondaryCategory = null;
                    }
                } else {
                    if (isCategoryOpen) {
                        item.icon = NavigationAdapter.ICON_MULTIPLE_OPEN;
                    } else {
                        item.label = currentPrimaryCategory;
                        item.id = "category:" + item.label;
                    }
                    items.add(item);
                    lastPrimaryCategory = item;
                    lastSecondaryCategory = null;
                }
            }
            return items;
        }

        @Override
        protected void onPostExecute(List<NavigationItem> items) {
            adapterCategories.setItems(items);
        }
    }

    private void setupNavigationMenu() {
        final NavigationItem itemTrashbin = new NavigationItem("trashbin", getString(R.string.action_trashbin), null, R.drawable.ic_delete_grey600_24dp);
        final NavigationItem itemSettings = new NavigationItem("settings", getString(R.string.action_settings), null, R.drawable.ic_settings_grey600_24dp);
        final NavigationItem itemAbout = new NavigationItem("about", getString(R.string.simple_about), null, R.drawable.ic_info_outline_grey600_24dp);

        ArrayList<NavigationItem> itemsMenu = new ArrayList<>(3);
        itemsMenu.add(itemTrashbin);
        itemsMenu.add(itemSettings);
        itemsMenu.add(itemAbout);

        NavigationAdapter adapterMenu = new NavigationAdapter(this, new NavigationAdapter.ClickListener() {
            @Override
            public void onItemClick(NavigationItem item) {
                if (itemSettings.equals(item)) {
                    Intent settingsIntent = new Intent(getApplicationContext(), PreferencesActivity.class);
                    startActivityForResult(settingsIntent, server_settings);
                } else if (itemAbout.equals(item)) {
                    Intent aboutIntent = new Intent(getApplicationContext(), AboutActivity.class);
                    startActivityForResult(aboutIntent, about);
                } else if (itemTrashbin.equals(item) && localAccount != null) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(localAccount.getUrl() + "/index.php/apps/files/?dir=/&view=trashbin")));
                }
            }

            @Override
            public void onIconClick(NavigationItem item) {
                onItemClick(item);
            }
        });


        this.updateCurrentAccountAvatar();
        adapterMenu.setItems(itemsMenu);
        binding.navigationMenu.setAdapter(adapterMenu);
    }

    public void initList() {
        adapter = new ItemAdapter(this);
        listView.setAdapter(adapter);
        listView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void refreshLists() {
        refreshLists(false);
    }

    private void refreshLists(final boolean scrollToTop) {
        if (localAccount == null) {
            fabCreate.hide();
            adapter.removeAll();
            return;
        }
        View emptyContentView = binding.activityNotesListView.emptyContentView.getRoot();
        emptyContentView.setVisibility(View.GONE);
        binding.activityNotesListView.progressCircular.setVisibility(View.VISIBLE);
        fabCreate.show();
        String subtitle;
        if (navigationSelection.category != null) {
            if (navigationSelection.category.isEmpty()) {
                subtitle = getString(R.string.search_in_category, getString(R.string.action_uncategorized));
            } else {
                subtitle = getString(R.string.search_in_category, NoteUtil.extendCategory(navigationSelection.category));
            }
        } else if (navigationSelection.favorite != null && navigationSelection.favorite) {
            subtitle = getString(R.string.search_in_category, getString(R.string.label_favorites));
        } else {
            subtitle = getString(R.string.search_in_all);
        }
        activityBinding.searchText.setText(subtitle);
        CharSequence query = null;
        if (searchView != null && !searchView.isIconified() && searchView.getQuery().length() != 0) {
            query = searchView.getQuery();
        }

        NotesLoadedListener callback = (List<Item> notes, boolean showCategory, CharSequence searchQuery) -> {
            adapter.setShowCategory(showCategory);
            adapter.setHighlightSearchQuery(searchQuery);
            adapter.setItemList(notes);
            binding.activityNotesListView.progressCircular.setVisibility(View.GONE);
            if (notes.size() > 0) {
                emptyContentView.setVisibility(View.GONE);
            } else {
                emptyContentView.setVisibility(View.VISIBLE);
            }
            if (scrollToTop) {
                listView.scrollToPosition(0);
            }
        };
        new LoadNotesListTask(localAccount.getId(), getApplicationContext(), callback, navigationSelection, query).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        new LoadCategoryListTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }


    /**
     * Adds the Menu Items to the Action Bar.
     *
     * @param menu Menu
     * @return boolean
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_list_view, menu);
        // Associate searchable configuration with the SearchView
        final MenuItem item = menu.findItem(R.id.search);
        searchView = (SearchView) item.getActionView();

        final LinearLayout searchEditFrame = searchView.findViewById(R.id
                .search_edit_frame);

        searchEditFrame.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            int oldVisibility = -1;

            @Override
            public void onGlobalLayout() {
                int currentVisibility = searchEditFrame.getVisibility();

                if (currentVisibility != oldVisibility) {
                    if (currentVisibility == View.VISIBLE) {
                        fabCreate.hide();
                    } else {
                        new Handler().postDelayed(() -> fabCreate.show(), 150);
                    }

                    oldVisibility = currentVisibility;
                }
            }

        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                refreshLists();
                return true;
            }
        });
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            searchView.setQuery(intent.getStringExtra(SearchManager.QUERY), true);
        }
        super.onNewIntent(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AccountImporter.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    /**
     * Handles the Results of started Sub Activities (Created Note, Edited Note)
     *
     * @param requestCode int to distinguish between the different Sub Activities
     * @param resultCode  int Return Code
     * @param data        Intent
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Check which request we're responding to
        if (requestCode == create_note_cmd) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                //not need because of db.synchronisation in createActivity

                Bundle bundle = data.getExtras();
                if (bundle != null && bundle.containsKey(CREATED_NOTE)) {
                    DBNote createdNote = (DBNote) bundle.getSerializable(CREATED_NOTE);
                    if (createdNote != null) {
                        adapter.add(createdNote);
                    } else {
                        Log.w(TAG, "createdNote must not be null");
                    }
                } else {
                    Log.w(TAG, "Provide at least " + CREATED_NOTE);
                }
            }
            listView.scrollToPosition(0);
        } else if (requestCode == server_settings) {
            // Recreate activity completely, because theme switching makes problems when only invalidating the views.
            // @see https://github.com/stefan-niedermann/nextcloud-notes/issues/529
            recreate();
        } else {
            try {
                AccountImporter.onActivityResult(requestCode, resultCode, data, this, (ssoAccount) -> {
                    CapabilitiesWorker.update(this);
                    new Thread(() -> {
                        Log.i(TAG, "Added account: " + "name:" + ssoAccount.name + ", " + ssoAccount.url + ", userId" + ssoAccount.userId);
                        try {
                            Log.i(TAG, "Refreshing capabilities for " + ssoAccount.name);
                            final Capabilities capabilities = CapabilitiesClient.getCapabilities(getApplicationContext(), ssoAccount, null);
                            db.addAccount(ssoAccount.url, ssoAccount.userId, ssoAccount.name, capabilities);
                            Log.i(TAG, capabilities.toString());
                            runOnUiThread(() -> {
                                selectAccount(ssoAccount.name);
                                binding.accountNavigation.setVisibility(View.VISIBLE);
                                binding.drawerLayout.closeDrawer(GravityCompat.START);
                            });
                        } catch (SQLiteConstraintException e) {
                            if (db.getAccounts().size() > 1) { // TODO ideally only show snackbar when this is a not migrated account
                                runOnUiThread(() -> {
                                    BrandedSnackbar.make(coordinatorLayout, R.string.account_already_imported, Snackbar.LENGTH_LONG).show();
                                    selectAccount(ssoAccount.name);
                                    binding.accountNavigation.setVisibility(View.VISIBLE);
                                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                                });
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> {
                                binding.accountNavigation.setVisibility(View.GONE);
                                binding.drawerLayout.openDrawer(GravityCompat.START);
                                binding.activityNotesListView.progressCircular.setVisibility(View.GONE);
                                ExceptionDialogFragment.newInstance(e).show(getSupportFragmentManager(), ExceptionDialogFragment.class.getSimpleName());
                            });
                        }
                    }).start();
                });
            } catch (AccountImportCancelledException e) {
                Log.i(TAG, "AccountImport has been cancelled.");
            }
        }
    }

    private void updateCurrentAccountAvatar() {
        try {
            String url = localAccount.getUrl();
            if (url != null) {
                Glide
                        .with(this)
                        .load(url + "/index.php/avatar/" + Uri.encode(localAccount.getUserName()) + "/64")
                        .error(R.drawable.ic_account_circle_grey_24dp)
                        .apply(RequestOptions.circleCropTransform())
                        .into(activityBinding.launchAccountSwitcher);
            } else {
                Log.w(TAG, "url is null");
            }
        } catch (NullPointerException e) { // No local account - show generic header
            Glide
                    .with(this)
                    .load(R.drawable.ic_account_circle_grey_24dp)
                    .apply(RequestOptions.circleCropTransform())
                    .into(activityBinding.launchAccountSwitcher);
            Log.w(TAG, "Tried to update username in drawer, but localAccount was null");
        }
    }

    @Override
    public void onNoteClick(int position, View v) {
        boolean hasCheckedItems = adapter.getSelected().size() > 0;
        if (hasCheckedItems) {
            if (!adapter.select(position)) {
                v.setSelected(false);
                adapter.deselect(position);
            } else {
                v.setSelected(true);
            }
            int size = adapter.getSelected().size();
            if (size > 0) {
                mActionMode.setTitle(getResources().getQuantityString(R.plurals.ab_selected, size, size));
            } else {
                mActionMode.finish();
            }
        } else {
            DBNote note = (DBNote) adapter.getItem(position);
            Intent intent = new Intent(getApplicationContext(), EditNoteActivity.class);
            intent.putExtra(EditNoteActivity.PARAM_NOTE_ID, note.getId());
            startActivityForResult(intent, show_single_note_cmd);

        }
    }

    @Override
    public void onNoteFavoriteClick(int position, View view) {
        DBNote note = (DBNote) adapter.getItem(position);
        NotesDatabase db = NotesDatabase.getInstance(view.getContext());
        db.toggleFavorite(ssoAccount, note, syncCallBack);
        adapter.notifyItemChanged(position);
        refreshLists();
    }

    @Override
    public boolean onNoteLongClick(int position, View v) {
        boolean selected = adapter.select(position);
        if (selected) {
            v.setSelected(true);
            mActionMode = startSupportActionMode(new MultiSelectedActionModeCallback(
                    this, this, db, mActionMode, adapter, listView, this::refreshLists, getSupportFragmentManager(), searchView
            ));
            int checkedItemCount = adapter.getSelected().size();
            mActionMode.setTitle(getResources().getQuantityString(R.plurals.ab_selected, checkedItemCount, checkedItemCount));
        }
        return selected;
    }

    @Override
    public void onBackPressed() {
        if (searchView == null || searchView.isIconified()) {
            super.onBackPressed();
        } else {
            activityBinding.homeToolbar.setVisibility(View.VISIBLE);
            activityBinding.toolbar.setVisibility(View.GONE);
            searchView.setIconified(true);
            ViewCompat.setElevation(activityBinding.appBar, 0);
            invalidateOptionsMenu();
        }
    }

    private void synchronize() {
        NoteServerSyncHelper syncHelper = db.getNoteServerSyncHelper();
        if (syncHelper.isSyncPossible()) {
            swipeRefreshLayout.setRefreshing(true);
            syncHelper.addCallbackPull(ssoAccount, syncCallBack);
            syncHelper.scheduleSync(ssoAccount, false);
        } else { // Sync is not possible
            swipeRefreshLayout.setRefreshing(false);
            if (syncHelper.isNetworkConnected() && syncHelper.isSyncOnlyOnWifi()) {
                Log.d(TAG, "Network is connected, but sync is not possible");
            } else {
                Log.d(TAG, "Sync is not possible, because network is not connected");
                BrandedSnackbar.make(coordinatorLayout, getString(R.string.error_sync, getString(R.string.error_no_network)), Snackbar.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void addAccount() {
        askForNewAccount(this);
    }

    @Override
    public void onAccountChosen(LocalAccount localAccount) {
        binding.drawerLayout.closeDrawer(GravityCompat.START);
        selectAccount(localAccount.getAccountName());
    }

    @Override
    public void onAccountDeleted(LocalAccount localAccount) {
        db.deleteAccount(localAccount);
        if (localAccount.getId() == this.localAccount.getId()) {
            List<LocalAccount> remainingAccounts = db.getAccounts();
            if (remainingAccounts.size() > 0) {
                this.localAccount = remainingAccounts.get(0);
                selectAccount(this.localAccount.getAccountName());
            } else {
                selectAccount(null);
                askForNewAccount(this);
            }
        }
    }

    @Override
    public void moveToAccount(LocalAccount account) {
        List<Integer> selection = new ArrayList<>(adapter.getSelected());

        adapter.deselect(0);
        for (Integer i : selection) {
            DBNote note = (DBNote) adapter.getItem(i);
            db.moveNoteToAnotherAccount(ssoAccount, note.getAccountId(), db.getNote(note.getAccountId(), note.getId()), account.getId());
            RecyclerView.ViewHolder viewHolder = listView.findViewHolderForAdapterPosition(i);
            if (viewHolder != null) {
                viewHolder.itemView.setSelected(false);
            } else {
                Log.w(TAG, "Could not found viewholder to remove selection");
            }
        }

        mActionMode.finish();
        searchView.setIconified(true);
        refreshLists();
    }
}
