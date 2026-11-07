@php
    $hostData = get_defined_vars();
    unset($hostData['__env'], $hostData['__data'], $hostData['__path'], $hostData['nodeAddressSyncOriginalPath']);
    $hostHtml = app('view')->file($nodeAddressSyncOriginalPath, $hostData)->render();
    $extension = '<script type="module" data-node-address-sync src="/api/v1/node-address-sync/assets/admin.js?v=1.2.1"></script>';
    $bodyEnd = strripos($hostHtml, '</body>');
    echo $bodyEnd === false ? $hostHtml . $extension
        : substr($hostHtml, 0, $bodyEnd) . $extension . substr($hostHtml, $bodyEnd);
@endphp
