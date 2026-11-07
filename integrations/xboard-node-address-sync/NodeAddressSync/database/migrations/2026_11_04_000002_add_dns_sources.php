<?php
use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('node_address_sync_bindings', function (Blueprint $t) {
            // Upgrades never enable or reinterpret an existing agent binding.
            $t->string('source_mode', 16)->default('agent');
            $t->string('source_domain', 253)->nullable();
            $t->string('record_type', 4)->default('A');
            $t->string('selection', 24)->default('single');
            $t->boolean('dns_only_confirmed')->default(false);
            $t->unsignedInteger('revision')->default(1);
            $t->string('candidate_ip', 45)->nullable();
            $t->unsignedBigInteger('candidate_since_ms')->default(0);
            $t->unsignedInteger('candidate_samples')->default(0);
            $t->unsignedBigInteger('checked_at_ms')->default(0);
            $t->unsignedBigInteger('saved_at_ms')->default(0);
            $t->string('dns_status', 40)->nullable();
            $t->text('dns_detail')->nullable();
        });
        Schema::create('node_address_sync_events', function (Blueprint $t) {
            $t->bigIncrements('id'); $t->uuid('binding_id')->nullable()->index();
            $t->string('kind', 40); $t->text('detail');
            $t->unsignedBigInteger('at_ms')->index();
        });
    }
    public function down(): void
    {
        Schema::dropIfExists('node_address_sync_events');
        Schema::table('node_address_sync_bindings', fn(Blueprint $t) => $t->dropColumn([
            'source_mode','source_domain','record_type','selection','dns_only_confirmed','revision',
            'candidate_ip','candidate_since_ms','candidate_samples','checked_at_ms','saved_at_ms','dns_status','dns_detail',
        ]));
    }
};
