/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
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
package org.floens.chan.ui.controller;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.floens.chan.R;
import org.floens.chan.core.presenter.SitesSetupPresenter;
import org.floens.chan.core.site.Site;
import org.floens.chan.core.site.SiteIcon;
import org.floens.chan.ui.layout.SiteAddLayout;
import org.floens.chan.ui.toolbar.ToolbarMenu;
import org.floens.chan.ui.toolbar.ToolbarMenuItem;
import org.floens.chan.ui.view.FloatingMenuItem;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static org.floens.chan.Chan.getGraph;
import static org.floens.chan.ui.theme.ThemeHelper.theme;
import static org.floens.chan.utils.AndroidUtils.setRoundItemBackground;

public class SitesSetupController extends StyledToolbarNavigationController implements SitesSetupPresenter.Callback, ToolbarMenuItem.ToolbarMenuItemCallback, View.OnClickListener {
    private static final int DONE_ID = 1;

    @Inject
    SitesSetupPresenter presenter;

    private ToolbarMenuItem doneMenuItem;

    private RecyclerView sitesRecyclerview;
    private FloatingActionButton addButton;

    private SitesAdapter sitesAdapter;
    private List<Site> sites = new ArrayList<>();

    public SitesSetupController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        getGraph().inject(this);

        // Inflate
        view = inflateRes(R.layout.controller_sites_setup);

        // Navigation
        navigationItem.setTitle(R.string.setup_sites_title);

        // View binding
        sitesRecyclerview = view.findViewById(R.id.sites_recycler);
        addButton = view.findViewById(R.id.add);

        // Adapters
        sitesAdapter = new SitesAdapter();

        // View setup
        sitesRecyclerview.setLayoutManager(new LinearLayoutManager(context));
        sitesRecyclerview.setAdapter(sitesAdapter);
        addButton.setOnClickListener(this);
        theme().applyFabColor(addButton);

        // Presenter
        presenter.create(this);
    }

    public void showDoneCheckmark() {
        navigationItem.swipeable = false;
        navigationItem.menu = new ToolbarMenu(context);
        doneMenuItem = navigationItem.menu.addItem(
                new ToolbarMenuItem(context, this, DONE_ID, 0, R.drawable.ic_done_white_24dp));
        doneMenuItem.getView().setAlpha(0f);
    }

    @Override
    public void onMenuItemClicked(ToolbarMenuItem item) {
        if ((Integer) item.getId() == DONE_ID) {
            presenter.onDoneClicked();
        }
    }

    @Override
    public void onSubMenuItemClicked(ToolbarMenuItem parent, FloatingMenuItem item) {
    }

    @Override
    public void onClick(View v) {
        if (v == addButton) {
            presenter.onShowDialogClicked();
        }
    }

    @Override
    public boolean onBack() {
        if (presenter.mayExit()) {
            return super.onBack();
        } else {
            return true;
        }
    }

    @Override
    public void showAddDialog() {
        @SuppressLint("InflateParams") final SiteAddLayout dialogView =
                (SiteAddLayout) LayoutInflater.from(context)
                        .inflate(R.layout.layout_site_add, null);

        dialogView.setPresenter(presenter);

        new AlertDialog.Builder(context)
                .setView(dialogView)
                .setTitle(R.string.setup_sites_add_title)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialogView.onPositiveClicked();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void dismiss() {
        navigationController.stopPresenting();
    }

    @Override
    public void openSiteConfiguration(Site site) {
        SiteSetupController c = new SiteSetupController(context);
        c.setSite(site);
        navigationController.pushController(c);
    }

    @Override
    public void setAddedSites(List<Site> sites) {
        this.sites.clear();
        this.sites.addAll(sites);
        sitesAdapter.notifyDataSetChanged();
    }

    @Override
    public void setNextAllowed(boolean nextAllowed, boolean animate) {
        if (doneMenuItem != null) {
            doneMenuItem.getView().animate().alpha(nextAllowed ? 1f : 0f).start();
        }
    }

    private void onSiteCellSettingsClicked(Site site) {
        presenter.onSiteCellSettingsClicked(site);
    }

    private class SitesAdapter extends RecyclerView.Adapter<SiteCell> {
        public SitesAdapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return sites.get(position).id();
        }

        @Override
        public SiteCell onCreateViewHolder(ViewGroup parent, int viewType) {
            return new SiteCell(LayoutInflater.from(context).inflate(R.layout.cell_site, parent, false));
        }

        @Override
        public void onBindViewHolder(SiteCell holder, int position) {
            Site site = sites.get(position);
            holder.setSite(site);
            holder.setSiteIcon(site);
            holder.text.setText(site.name());

            int boards = presenter.getSiteBoardCount(site);
            String boardsString = context.getResources().getQuantityString(R.plurals.board, boards, boards);
            String descriptionText = context.getString(R.string.setup_sites_site_description, boardsString);
            holder.description.setText(descriptionText);
        }

        @Override
        public int getItemCount() {
            return sites.size();
        }
    }

    private class SiteCell extends RecyclerView.ViewHolder implements View.OnClickListener {
        private ImageView image;
        private TextView text;
        private TextView description;
        private SiteIcon siteIcon;
        private ImageView settings;

        private Site site;

        public SiteCell(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image);
            text = itemView.findViewById(R.id.text);
            description = itemView.findViewById(R.id.description);
            settings = itemView.findViewById(R.id.settings);
            setRoundItemBackground(settings);
            theme().settingsDrawable.apply(settings);
            settings.setOnClickListener(this);
        }

        private void setSite(Site site) {
            this.site = site;
        }

        private void setSiteIcon(Site site) {
            siteIcon = site.icon();
            siteIcon.get(new SiteIcon.SiteIconResult() {
                @Override
                public void onSiteIcon(SiteIcon siteIcon, Drawable icon) {
                    if (SiteCell.this.siteIcon == siteIcon) {
                        image.setImageDrawable(icon);
                    }
                }
            });
        }

        @Override
        public void onClick(View v) {
            if (v == settings) {
                onSiteCellSettingsClicked(site);
            }
        }
    }
}
