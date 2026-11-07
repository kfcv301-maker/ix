<?php

namespace Plugin\NodeAddressSync\Services;

final class AddressPolicy
{
    public static function normalize(string $ip): ?string
    {
        $ip = trim($ip, '[]');
        if (!filter_var($ip, FILTER_VALIDATE_IP)) return null;
        return strtolower(inet_ntop(inet_pton($ip)));
    }

    public static function inNetwork(string $ip, string $network): bool
    {
        [$net, $bits] = explode('/', $network);
        $a = @inet_pton($ip); $b = @inet_pton($net);
        if ($a === false || $b === false || strlen($a) !== strlen($b)) return false;
        $bytes = intdiv((int) $bits, 8); $rest = (int) $bits % 8;
        return substr($a, 0, $bytes) === substr($b, 0, $bytes)
            && (!$rest || ((ord($a[$bytes]) ^ ord($b[$bytes])) & (255 << (8 - $rest))) === 0);
    }

    public static function validPublic(string $ip): bool
    {
        $ip = self::normalize($ip);
        if (!$ip) return false;
        if (str_contains($ip, ':')) {
            if (!self::inNetwork($ip, '2000::/3')) return false;
            foreach (['2001::/23', '2001:db8::/32', '2002::/16', '3fff::/20'] as $net) {
                if (self::inNetwork($ip, $net)) return false;
            }
        } elseif (!self::validPublicIpv4($ip)) return false;
        // Cloudflare proxy ranges, checked against the official lists 2026-11-04.
        foreach (json_decode(file_get_contents(dirname(__DIR__) . '/dns/cloudflare-networks.json'), true) as $net) {
            if (self::inNetwork($ip, $net)) return false;
        }
        return true;
    }

    public static function select(array $answers, string $type, string $rule): string
    {
        abort_unless(in_array($type, ['A','AAAA'], true) && in_array($rule, ['single','lowest'], true), 422, '解析规则无效');
        abort_if(!$answers || count($answers) > 64, 422, 'DNS 结果为空或过多');
        $ips = [];
        foreach ($answers as $value) {
            $ip = is_string($value) ? self::normalize($value) : null;
            abort_unless($ip && self::validPublic($ip) && (str_contains($ip, ':') === ($type === 'AAAA')), 422, 'DNS 包含非公网、代理或错误类型地址');
            $ips[] = $ip;
        }
        $ips = array_values(array_unique($ips));
        usort($ips, fn($a, $b) => strcmp(inet_pton($a), inet_pton($b)));
        abort_if($rule === 'single' && count($ips) !== 1, 422, 'DNS 返回多个地址，需要明确选择规则');
        return $ips[0];
    }

    public static function needsSni(\App\Models\Server $node): bool
    {
        if (self::normalize($node->host)) return false;
        $p = $node->protocol_settings ?? [];
        $tls = !empty($p['tls']) || in_array($node->type, ['trojan','hysteria','tuic','anytls','naive'], true);
        $sni = in_array($node->type,['trojan','hysteria','tuic','anytls','naive'],true)
            ? data_get($p,'tls.server_name') : data_get($p,'tls_settings.server_name');
        return $tls && !$sni && empty($p['reality_settings']);
    }

    public static function validPublicIpv4(string $ip): bool
    {
        if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4 | FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) {
            return false;
        }
        $parts = array_map('intval', explode('.', $ip));
        // PHP's NO_RES_RANGE also needs explicit CGNAT, multicast, and documentation checks.
        return !($parts[0] >= 224
            || ($parts[0] === 100 && $parts[1] >= 64 && $parts[1] <= 127)
            || ($parts[0] === 192 && $parts[1] === 0 && in_array($parts[2], [0, 2], true))
            || ($parts[0] === 198 && in_array($parts[1], [18, 19], true))
            || ($parts[0] === 198 && $parts[1] === 51 && $parts[2] === 100)
            || ($parts[0] === 203 && $parts[1] === 0 && $parts[2] === 113));
    }
}
