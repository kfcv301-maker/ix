<?php

namespace Plugin\NodeAddressSync\Services;

use App\Models\Plugin;
use Illuminate\View\View;

class AdminViewComposer
{
    public function compose(View $view): void
    {
        // A worker may retain this composer after the plugin was disabled.
        if (!Plugin::where('code', 'node_address_sync')->value('is_enabled')) {
            return;
        }
        $wrapper = dirname(__DIR__) . '/resources/views/admin-wrapper.blade.php';
        if ($view->getPath() !== $wrapper) {
            $view->with('nodeAddressSyncOriginalPath', $view->getPath());
            $view->setPath($wrapper);
        }
    }
}
