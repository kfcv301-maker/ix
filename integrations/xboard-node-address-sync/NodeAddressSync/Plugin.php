<?php

namespace Plugin\NodeAddressSync;

use App\Services\Plugin\AbstractPlugin;

class Plugin extends AbstractPlugin
{
    public function boot(): void
    {
        // Xboard loads the main class directly. Keep sub-classes loadable even
        // with an authoritative Composer classmap after a ZIP installation.
        static $registered = false;
        if (!$registered) {
            $base = __DIR__;
            spl_autoload_register(static function (string $class) use ($base): void {
                $prefix = 'Plugin\\NodeAddressSync\\';
                if (str_starts_with($class, $prefix)) {
                    $file = $base . '/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
                    if (is_file($file)) {
                        require_once $file;
                    }
                }
            }, true, true);
            $registered = true;
        }

        // One listener per view factory, including long-lived Octane workers.
        static $factories;
        $factories ??= new \WeakMap();
        $factory = app('view');
        if (!isset($factories[$factory])) {
            $factory->composer('admin', \Plugin\NodeAddressSync\Services\AdminViewComposer::class);
            $factories[$factory] = true;
        }
    }
}
