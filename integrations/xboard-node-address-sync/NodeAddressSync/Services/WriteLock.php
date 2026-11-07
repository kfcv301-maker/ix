<?php
namespace Plugin\NodeAddressSync\Services;

final class WriteLock
{
    public static function acquire(): void
    {
        // Preserve the site's existing SQLite contention fix; MySQL uses row locks.
        if (class_exists(\App\Support\SqliteWriteLock::class)) {
            \App\Support\SqliteWriteLock::acquire();
        } elseif (\Illuminate\Support\Facades\DB::getDriverName() === 'sqlite') {
            \Illuminate\Support\Facades\DB::statement('UPDATE node_address_sync_bindings SET revision=revision WHERE 0');
        }
    }
}
