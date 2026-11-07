<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('node_address_sync_bindings', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->string('label', 100);
            $table->string('instance_id', 64)->nullable();
            $table->string('token_hash', 64);
            $table->boolean('enabled')->default(true);
            $table->string('last_ip', 45)->nullable();
            $table->unsignedBigInteger('last_observed_at')->default(0);
            $table->timestamp('last_seen_at')->nullable();
            $table->timestamps();
        });
        Schema::create('node_address_sync_nodes', function (Blueprint $table) {
            $table->unsignedInteger('node_id')->primary();
            $table->uuid('binding_id')->index();
            $table->string('expected_host', 255);
        });
        Schema::create('node_address_sync_history', function (Blueprint $table) {
            $table->bigIncrements('id');
            $table->uuid('binding_id')->index();
            $table->unsignedInteger('node_id');
            $table->string('old_host', 255);
            $table->string('new_host', 255);
            $table->timestamp('created_at')->index();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('node_address_sync_history');
        Schema::dropIfExists('node_address_sync_nodes');
        Schema::dropIfExists('node_address_sync_bindings');
    }
};
