/*
 * Copyright (C) 2017 Vincent Breitmoser <look@my.amazin.horse>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.token;


import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.Loader;

import org.sufficientlysecure.keychain.operations.results.GenericOperationResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.securitytoken.SecurityTokenInfo;
import org.sufficientlysecure.keychain.ui.token.ManageSecurityTokenContract.ManageSecurityTokenMvpPresenter;
import org.sufficientlysecure.keychain.ui.token.ManageSecurityTokenContract.ManageSecurityTokenMvpView;
import org.sufficientlysecure.keychain.ui.token.ManageSecurityTokenFragment.StatusLine;
import org.sufficientlysecure.keychain.ui.token.PublicKeyRetrievalLoader.ContentUriRetrievalLoader;
import org.sufficientlysecure.keychain.ui.token.PublicKeyRetrievalLoader.KeyRetrievalResult;
import org.sufficientlysecure.keychain.ui.token.PublicKeyRetrievalLoader.KeyserverRetrievalLoader;
import org.sufficientlysecure.keychain.ui.token.PublicKeyRetrievalLoader.LocalKeyLookupLoader;
import org.sufficientlysecure.keychain.ui.token.PublicKeyRetrievalLoader.UriKeyRetrievalLoader;
import org.sufficientlysecure.keychain.ui.util.PermissionsUtil;


class ManageSecurityTokenPresenter implements ManageSecurityTokenMvpPresenter {
    private static final int LOADER_LOCAL = 0;
    private static final int LOADER_URI = 1;
    private static final int LOADER_KEYSERVER = 2;
    private static final int LOADER_CONTENT_URI = 3;
    private static final String ARG_CONTENT_URI = "content_uri";


    private final Context context;
    private final LoaderManager loaderManager;
    private final SecurityTokenInfo tokenInfo;


    private ManageSecurityTokenMvpView view;

    private boolean searchedLocally;
    private boolean searchedAtUri;
    private boolean searchedKeyservers;

    private byte[] importKeyData;
    private Long masterKeyId;

    private OperationLog log;
    private Uri selectedContentUri;

    ManageSecurityTokenPresenter(Context context, LoaderManager loaderManager, SecurityTokenInfo tokenInfo) {
        this.context = context.getApplicationContext();
        this.loaderManager = loaderManager;
        this.tokenInfo = tokenInfo;

        this.log = new OperationLog();
    }

    @Override
    public void setView(ManageSecurityTokenMvpView view) {
        this.view = view;
    }

    @Override
    public void onActivityCreated() {
        if (!searchedLocally || !searchedAtUri || !searchedKeyservers) {
            continueSearch();
        }
    }

    private void continueSearchAfterError() {
        view.statusLineError();
        continueSearch();
    }

    private void continueSearch() {
        if (!searchedLocally) {
            view.statusLineAdd(StatusLine.SEARCH_LOCAL);
            loaderManager.restartLoader(LOADER_LOCAL, null, loaderCallbacks);
            return;
        }

        if (!searchedAtUri) {
            view.statusLineAdd(StatusLine.SEARCH_URI);
            loaderManager.restartLoader(LOADER_URI, null, loaderCallbacks);
            return;
        }

        if (!searchedKeyservers) {
            view.statusLineAdd(StatusLine.SEARCH_KEYSERVER);
            loaderManager.restartLoader(LOADER_KEYSERVER, null, loaderCallbacks);
            return;
        }

        view.showActionRetryOrFromFile();
    }

    private LoaderCallbacks<KeyRetrievalResult> loaderCallbacks = new LoaderCallbacks<KeyRetrievalResult>() {
        @Override
        public Loader<KeyRetrievalResult> onCreateLoader(int id, Bundle args) {
            switch (id) {
                case LOADER_LOCAL:
                    return new LocalKeyLookupLoader(context, tokenInfo.getAllFingerprints());
                case LOADER_URI:
                    return new UriKeyRetrievalLoader(context, tokenInfo.getUrl(), tokenInfo.getAllFingerprints());
                case LOADER_KEYSERVER:
                    return new KeyserverRetrievalLoader(context, tokenInfo.getFingerprintSign());
                case LOADER_CONTENT_URI:
                    return new ContentUriRetrievalLoader(context, tokenInfo.getFingerprintSign(),
                            args.<Uri>getParcelable(ARG_CONTENT_URI));
            }
            throw new IllegalArgumentException("called with unknown loader id!");
        }

        @Override
        public void onLoadFinished(Loader<KeyRetrievalResult> loader, KeyRetrievalResult data) {
            if (view == null) {
                return;
            }

            switch (loader.getId()) {
                case LOADER_LOCAL: {
                    searchedLocally = true;
                    break;
                }
                case LOADER_URI: {
                    searchedAtUri = true;
                    break;
                }
                case LOADER_KEYSERVER: {
                    searchedKeyservers = true;
                    break;
                }
                case LOADER_CONTENT_URI: {
                    // nothing to do here
                    break;
                }
                default: {
                    throw new IllegalArgumentException("called with unknown loader id!");
                }
            }

            log.add(data.getOperationResult(), 0);

            if (data.isSuccess()) {
                processResult(data);
            } else {
                continueSearchAfterError();
            }
        }

        @Override
        public void onLoaderReset(Loader<KeyRetrievalResult> loader) {

        }
    };

    private void processResult(KeyRetrievalResult result) {
        view.statusLineOk();

        byte[] importKeyData = result.getKeyData();
        Long masterKeyId = result.getMasterKeyId();
        if (importKeyData != null && masterKeyId != null) {
            view.showActionImport();
            this.importKeyData = importKeyData;
            this.masterKeyId = masterKeyId;
            return;
        }

        if (masterKeyId != null) {
            this.masterKeyId = masterKeyId;
            view.statusLineAdd(StatusLine.TOKEN_CHECK);
            view.operationPromote(masterKeyId, tokenInfo.getAid());
            return;
        }

        throw new IllegalArgumentException("Method can only be called with successful result!");
    }

    @Override
    public void onClickImport() {
        view.statusLineAdd(StatusLine.IMPORT);
        view.hideAction();
        view.operationImportKey(importKeyData);
    }

    @Override
    public void onImportSuccess(OperationResult result) {
        log.add(result, 0);

        view.statusLineOk();
        view.statusLineAdd(StatusLine.TOKEN_PROMOTE);
        view.operationPromote(masterKeyId, tokenInfo.getAid());
    }

    @Override
    public void onImportError(OperationResult result) {
        log.add(result, 0);

        view.statusLineError();
    }

    @Override
    public void onPromoteSuccess(OperationResult result) {
        log.add(result, 0);

        view.statusLineOk();
        view.showActionViewKey();
    }

    @Override
    public void onPromoteError(OperationResult result) {
        log.add(result, 0);

        view.statusLineError();
    }

    @Override
    public void onClickRetry() {
        searchedLocally = false;
        searchedAtUri = false;
        searchedKeyservers = false;

        view.hideAction();
        view.resetStatusLines();
        continueSearch();
    }

    @Override
    public void onClickViewKey() {
        view.finishAndShowKey(masterKeyId);
    }

    @Override
    public void onClickResetToken() {
        view.showConfirmResetDialog();
    }

    @Override
    public void onClickConfirmReset() {
        view.operationResetSecurityToken();
    }

    @Override
    public void onSecurityTokenResetSuccess() {
        // TODO
    }

    @Override
    public void onClickLoadFile() {
        view.showFileSelectDialog();
    }

    @Override
    public void onFileSelected(Uri contentUri) {
        boolean hasReadPermission = PermissionsUtil.checkReadPermission(context, contentUri);
        if (!hasReadPermission) {
            selectedContentUri = contentUri;
            view.requestStoragePermission();
            return;
        }

        startLoadingFile(contentUri);
    }

    private void startLoadingFile(Uri contentUri) {
        view.resetStatusLines();
        view.statusLineAdd(StatusLine.SEARCH_CONTENT_URI);

        Bundle args = new Bundle();
        args.putParcelable(ARG_CONTENT_URI, contentUri);
        loaderManager.restartLoader(LOADER_CONTENT_URI, args, loaderCallbacks);
    }

    @Override
    public void onStoragePermissionGranted() {
        Uri contentUri = selectedContentUri;
        selectedContentUri = null;
        startLoadingFile(contentUri);
    }

    @Override
    public void onStoragePermissionDenied() {
        selectedContentUri = null;
    }

    @Override
    public void onClickViewLog() {
        OperationResult result = new GenericOperationResult(GenericOperationResult.RESULT_OK, log);
        view.showDisplayLogActivity(result);
    }
}
