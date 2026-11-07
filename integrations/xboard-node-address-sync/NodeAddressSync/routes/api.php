<?php

use Illuminate\Support\Facades\Route;
use Plugin\NodeAddressSync\Controllers\SyncController;

Route::prefix('api/v1/node-address-sync')->group(function (): void {
    // Public code assets contain no credentials; UI and data still require admin.
    Route::get('assets/{name}', [SyncController::class, 'asset'])->where('name', 'admin\.js|manager\.js|bootstrap\.sh');
    // Server tokens have access only to the report endpoint and their own binding.
    Route::post('report', [SyncController::class, 'report'])->middleware('throttle:120,1');
    Route::post('connection/check', [SyncController::class, 'connectionCheck'])->middleware('throttle:120,1');
    Route::post('connection/report', [SyncController::class, 'connectionReport'])->middleware('throttle:1200,1');
    Route::middleware(['admin', 'throttle:120,1'])->group(function (): void {
        Route::get('ui', [SyncController::class, 'ui']);
        Route::get('state', [SyncController::class, 'state']);
        Route::post('bindings/save', [SyncController::class, 'save']);
        Route::post('bindings/delete', [SyncController::class, 'delete']);
        Route::post('bindings/rotate', [SyncController::class, 'rotate']);
        Route::post('connections/save', [SyncController::class, 'connectionSave']);
        Route::post('connections/rotate', [SyncController::class, 'connectionRotate']);
        Route::post('connections/delete', [SyncController::class, 'connectionDelete']);
    });
});
