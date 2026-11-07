<?php

use Illuminate\Support\Facades\Route;
use Plugin\NodeAddressSync\Controllers\SyncController;

// Previous bookmarks reopen the integrated admin page; no separate login UI.
Route::get('node-address-sync', [SyncController::class, 'page']);
