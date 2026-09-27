/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grocy Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grocy Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2020-2024 by Patrick Zedler and Dominic Zedler
 * Copyright (c) 2024-2026 by Patrick Zedler
 */

package xyz.zedler.patrick.grocy.viewmodel;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import me.xdrop.fuzzywuzzy.model.BoundExtractedResult;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.OpenBeautyFactsProduct;
import xyz.zedler.patrick.grocy.model.OpenFoodFactsProduct;
import xyz.zedler.patrick.grocy.model.PendingProduct;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.repository.ChooseProductRepository;
import xyz.zedler.patrick.grocy.util.PrefsUtil;
import xyz.zedler.patrick.grocy.util.SortUtil;

public class ChooseProductViewModel extends BaseViewModel {

  private static final String TAG = ChooseProductViewModel.class.getSimpleName();

  private final SharedPreferences sharedPrefs;
  private final DownloadHelper dlHelper;
  private final ChooseProductRepository repository;

  private final MutableLiveData<Boolean> displayHelpLive;
  private final MutableLiveData<Boolean> isLoadingLive;
  private final MutableLiveData<List<Product>> displayedItemsLive;
  private final MutableLiveData<String> productNameLive;
  private final MutableLiveData<Integer> productNameErrorLive;
  private final MutableLiveData<String> offHelpText;
  private final MutableLiveData<String> createProductTextLive;
  private final MutableLiveData<String> createPendingProductTextLive;
  private final MutableLiveData<Boolean> forbidCreateProductLive;
  private final MutableLiveData<String> productNameHelperTextLive;
  private final MutableLiveData<String> existingProductsCategoryTextLive;

  private final String barcode;
  private List<Product> products;
  private final HashMap<String, Product> productHashMap;
  private List<PendingProduct> pendingProducts;
  private final HashMap<String, PendingProduct> pendingProductHashMap;
  private final boolean forbidCreateProductInitial;
  private final boolean pendingProductsActive;
  private String nameFromOnlineSource;
  private final boolean debug;
  private boolean serverLookupAttempted = false;

  public ChooseProductViewModel(
      @NonNull Application application,
      String barcode,
      boolean forbidCreateProduct,
      boolean pendingProductsActive
  ) {
    super(application);

    sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
    debug = PrefsUtil.isDebuggingEnabled(sharedPrefs);

    displayHelpLive = new MutableLiveData<>(false);
    isLoadingLive = new MutableLiveData<>(false);
    dlHelper = new DownloadHelper(getApplication(), TAG, isLoadingLive::setValue, null);
    repository = new ChooseProductRepository(application);

    displayedItemsLive = new MutableLiveData<>();
    productNameLive = new MutableLiveData<>();
    productNameErrorLive = new MutableLiveData<>();
    offHelpText = new MutableLiveData<>();
    createProductTextLive = new MutableLiveData<>(getString(R.string.msg_create_new_product));
    createPendingProductTextLive = new MutableLiveData<>(
            getString(R.string.msg_create_new_pending_product)
    );
    forbidCreateProductLive = new MutableLiveData<>(forbidCreateProduct);
    productNameHelperTextLive = new MutableLiveData<>(
        application.getString(R.string.subtitle_barcode, barcode)
    );
    existingProductsCategoryTextLive = new MutableLiveData<>(
        getString(R.string.category_existing_products)
    );
    forbidCreateProductInitial = forbidCreateProduct;
    this.pendingProductsActive = pendingProductsActive;

    this.barcode = barcode;
    products = new ArrayList<>();
    productHashMap = new HashMap<>();
    pendingProductHashMap = new HashMap<>();
  }

  public void loadFromDatabase(boolean downloadAfterLoading) {
    repository.loadFromDatabase(data -> {
      this.products = data.getProducts();
      productHashMap.clear();
      for (Product product : products) {
        productHashMap.put(product.getName().toLowerCase(), product);
      }
      this.pendingProducts = data.getPendingProducts();
      pendingProductHashMap.clear();
      for (PendingProduct pendingProduct : this.pendingProducts) {
        pendingProductHashMap.put(pendingProduct.getName().toLowerCase(), pendingProduct);
      }
      displayItems();
      if (downloadAfterLoading) {
        downloadData(false);
      } else {
        fillProductNameIfPossible();
      }
    }, error -> onError(error, TAG));
  }

  public void downloadData(boolean forceUpdate) {
    dlHelper.updateData(
        updated -> {
          if (updated) {
            loadFromDatabase(false);
          } else {
            fillProductNameIfPossible();
          }
        },
        error -> onError(error, TAG),
        forceUpdate,
        false,
        Product.class
    );
  }

  public void displayItems() {
    String productName = productNameLive.getValue();

    if (productName == null || productName.isEmpty()) {
      SortUtil.sortProductsByName(products, true);
      if (products.size() > 30) {
        displayedItemsLive.setValue(products.subList(0, 30));
      } else {
        displayedItemsLive.setValue(products);
      }
      createProductTextLive.setValue(getString(R.string.msg_create_new_product));
      if (pendingProductsActive) {
        createPendingProductTextLive.setValue(
            getApplication().getString(R.string.msg_create_new_pending_product)
        );
      }
      if (!forbidCreateProductInitial) {
        forbidCreateProductLive.setValue(false);
      }
      existingProductsCategoryTextLive.setValue(getString(R.string.category_existing_products));
      return;
    } else if (productNameErrorLive.getValue() != null) {
      productNameErrorLive.setValue(null);
    }

    ArrayList<Product> allProducts = new ArrayList<>();
    allProducts.addAll(products);
    allProducts.addAll(pendingProducts);
    ArrayList<Product> suggestions = new ArrayList<>(30);
    List<BoundExtractedResult<Product>> results = FuzzySearch.extractTop(
            productName.toLowerCase(),
            allProducts,
            Product::getName,
            30,
            20
    );
    for (BoundExtractedResult<Product> result : results) {
      suggestions.add(result.getReferent());
    }

    displayedItemsLive.setValue(suggestions);
    createProductTextLive.setValue(
        getApplication().getString(R.string.msg_create_new_product_filled, productName)
    );
    if (pendingProductsActive) {
      createPendingProductTextLive.setValue(
          getApplication().getString(R.string.msg_create_new_pending_product_filled, productName)
      );
    }
    if (!forbidCreateProductInitial) {
      forbidCreateProductLive.setValue(
              productHashMap.containsKey(productName.toLowerCase())
                      || pendingProductHashMap.containsKey(productName.toLowerCase())
      );
    }
    existingProductsCategoryTextLive.setValue(
        getString(R.string.category_existing_products_similar)
    );
  }

  public void fillProductNameIfPossible() {
    boolean productNameFilled = productNameLive.getValue() != null
        && !productNameLive.getValue().isEmpty();
    if (productNameFilled) {
      return;
    }

    // Ask the server first: it runs the configured STOCK_BARCODE_LOOKUP_PLUGIN and
    // therefore knows sources the app cannot reach itself (e.g. regional databases).
    lookupOnServerThenOpenFoodFacts();
  }

  /**
   * Tries the server side external barcode lookup and falls back to the client side
   * Open Food Facts / Open Beauty Facts lookup.
   *
   * <p>Guarded by {@link #serverLookupAttempted} because this method sits in the
   * funnel that also runs after every data sync, and because the server rejects
   * add=true with "Product ... already exists" once the product is there.
   */
  private void lookupOnServerThenOpenFoodFacts() {
    if (serverLookupAttempted) {
      lookupOnOpenFoodFacts();
      return;
    }
    serverLookupAttempted = true;

    String url = new GrocyApi(getApplication())
        .getStockBarcodeExternalLookup(barcode, true);
    dlHelper.get(
        url,
        TAG,
        this::onServerLookupResponse,
        error -> {
          // Typical reasons: the product already exists on the server (name clash),
          // no permission, or no network. Sync once so that an already existing
          // product shows up in the list below and can be picked.
          downloadData(true);
        }
    );
  }

  private void onServerLookupResponse(String response) {
    if (response == null || response.trim().isEmpty()
        || "null".equals(response.trim())) {
      // Nothing found server side
      lookupOnOpenFoodFacts();
      return;
    }

    try {
      JSONObject product = new JSONObject(response);
      String name = product.optString("name", "");
      if (name.isEmpty()) {
        lookupOnOpenFoodFacts();
        return;
      }

      productNameLive.setValue(name);
      nameFromOnlineSource = name;

      // grocy returns the new product id as a JSON string, so parse it explicitly
      int id = 0;
      try {
        id = Integer.parseInt(product.optString("id", "0"));
      } catch (NumberFormatException ignored) {
        // no id in response, the product was not created
      }

      offHelpText.setValue(id > 0
          ? getString(R.string.msg_product_name_server_created, id)
          : getString(R.string.msg_product_name_server));

      // Pull the product created on the server into the local database so it can
      // be selected from the list right away.
      downloadData(true);
    } catch (JSONException e) {
      lookupOnOpenFoodFacts();
    }
  }

  /**
   * Original client side lookup, unchanged apart from being extracted into its own
   * method so it can be used as fallback.
   */
  private void lookupOnOpenFoodFacts() {
    if (isOpenFoodFactsEnabled()) {
      OpenFoodFactsProduct.getOpenFoodFactsProduct(
          dlHelper,
          barcode,
          product -> {
            productNameLive.setValue(product.getLocalizedProductName(getApplication()));
            nameFromOnlineSource = product.getLocalizedProductName(getApplication());
            offHelpText.setValue(getString(R.string.msg_product_name_off));
          },
          error -> OpenBeautyFactsProduct.getOpenBeautyFactsProduct(
              dlHelper,
              barcode,
              product -> {
                String productName = product.getLocalizedProductName(getApplication());
                if (productName != null && !productName.isEmpty()) {
                  productNameLive.setValue(productName);
                  nameFromOnlineSource = productName;
                  offHelpText.setValue(getString(R.string.msg_product_name_obf));
                } else {
                  offHelpText.setValue(getString(R.string.msg_product_name_lookup_empty));
                  sendEvent(Event.FOCUS_INVALID_VIEWS);
                }
              },
              error1 -> {
                offHelpText.setValue(getString(R.string.msg_product_name_lookup_error));
                sendEvent(Event.FOCUS_INVALID_VIEWS);
              }
          )
      );
    } else {
      sendEvent(Event.FOCUS_INVALID_VIEWS);
    }
  }

  public void createPendingProduct(ChooseProductRepository.CreatePendingProductListener listener) {
    String name = productNameLive.getValue();
    if (name == null || name.trim().isEmpty()) {
      productNameErrorLive.setValue(R.string.error_empty);
      return;
    }
    PendingProduct pendingProduct = new PendingProduct(name, name.equals(nameFromOnlineSource));
    repository.createPendingProduct(
            pendingProduct,
            listener,
            error -> showMessage("Could not create temporary product")
    );
  }

  @NonNull
  public MutableLiveData<List<Product>> getDisplayedItemsLive() {
    return displayedItemsLive;
  }

  public MutableLiveData<Boolean> getDisplayHelpLive() {
    return displayHelpLive;
  }

  public void toggleDisplayHelpLive() {
    displayHelpLive.setValue(displayHelpLive.getValue() == null || !displayHelpLive.getValue());
  }

  public MutableLiveData<String> getProductNameLive() {
    return productNameLive;
  }

  public MutableLiveData<Integer> getProductNameErrorLive() {
    return productNameErrorLive;
  }

  public MutableLiveData<String> getOffHelpText() {
    return offHelpText;
  }

  public MutableLiveData<String> getCreateProductTextLive() {
    return createProductTextLive;
  }

  public MutableLiveData<String> getCreatePendingProductTextLive() {
    return createPendingProductTextLive;
  }

  public MutableLiveData<Boolean> getForbidCreateProductLive() {
    return forbidCreateProductLive;
  }

  public MutableLiveData<String> getProductNameHelperTextLive() {
    return productNameHelperTextLive;
  }

  public MutableLiveData<String> getExistingProductsCategoryTextLive() {
    return existingProductsCategoryTextLive;
  }

  public boolean isPendingProductsActive() {
    return pendingProductsActive;
  }

  @NonNull
  public MutableLiveData<Boolean> getIsLoadingLive() {
    return isLoadingLive;
  }

  public boolean isFeatureEnabled(String pref) {
    if (pref == null) {
      return true;
    }
    return sharedPrefs.getBoolean(pref, true);
  }

  @Override
  protected void onCleared() {
    dlHelper.destroy();
    super.onCleared();
  }

  public static class ChooseProductViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final String barcode;
    private final boolean forbidCreateProduct;
    private final boolean pendingProductsActive;

    public ChooseProductViewModelFactory(
        Application application,
        String barcode,
        boolean forbidCreateProduct,
        boolean pendingProductsActive
    ) {
      this.application = application;
      this.barcode = barcode;
      this.forbidCreateProduct = forbidCreateProduct;
      this.pendingProductsActive = pendingProductsActive;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new ChooseProductViewModel(
              application, barcode, forbidCreateProduct, pendingProductsActive
      );
    }
  }
}
