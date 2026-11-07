<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        // Existing bindings, credentials, source modes and enabled flags stay intact.
        Schema::create('node_address_sync_connections', function (Blueprint $t): void {
            $t->uuid('id')->primary();
            $t->string('label', 100);
            $t->uuid('relay_panel_id')->index();
            $t->string('relay_url', 255);
            $t->string('token_hash', 64);
            $t->boolean('enabled')->default(false);
            $t->unsignedInteger('revision')->default(1);
            $t->timestamps();
        });
        Schema::create('node_address_sync_relay_mappings', function (Blueprint $t): void {
            $t->uuid('binding_id')->primary();
            $t->uuid('connection_id')->index();
            $t->unsignedInteger('relay_node_id');
            $t->unique(['connection_id', 'relay_node_id'], 'nas_connection_relay_node_unique');
        });
    }

    public function down(): void
    {
        // A rollback must not silently turn Relay bindings into writable v1 bindings.
        if (DB::table('node_address_sync_relay_mappings')->exists()
            || DB::table('node_address_sync_bindings')->where('source_mode', 'relay')->exists()) {
            throw new RuntimeException('Remove Relay mappings explicitly before rolling back this migration');
        }
        Schema::dropIfExists('node_address_sync_relay_mappings');
        Schema::dropIfExists('node_address_sync_connections');
    }
};
