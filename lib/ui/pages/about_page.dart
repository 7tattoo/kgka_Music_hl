import 'package:flutter/material.dart';
import '../widgets/liquid_glass_ui.dart';
import '../design_tokens.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../config/app_config.dart';
import '../../services/music_api.dart';
import '../widgets/toast.dart';
import '../adaptive_layout.dart';

class AboutPage extends StatefulWidget {
  const AboutPage({super.key, required this.api});

  static final Uri _repositoryUri = Uri.parse(
    'https://github.com/umr-xiaomai/kgka_Music_hl',
  );

  final MusicApi api;

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  Future<void> _openRepository(BuildContext context) async {
    final opened = await launchUrl(
      AboutPage._repositoryUri,
      mode: LaunchMode.externalApplication,
    );
    if (!opened) {
      Toast.error('无法打开 GitHub 仓库链接');
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return LiquidGlassBackground(
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: AdaptiveContentPadding(
          child: CustomScrollView(
            slivers: [
              const SliverAppBar(
                pinned: true,
                title: Text('关于'),
                surfaceTintColor: Colors.transparent,
                backgroundColor: Colors.transparent,
              ),
            SliverToBoxAdapter(
              child: Column(
                children: [
                  const SizedBox(height: 12),
                  _AppLogo(),
                  const SizedBox(height: 16),
                  Text(
                    AppConfig.appName,
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '版本 ${AppConfig.appVersion}',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '一个专注播放体验的音乐应用。',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(22, 18, 22, 8),
                child: _InfoSection(
                  children: [
                    const _InfoRow(label: '应用名称', value: AppConfig.appName),
                    const _InfoRow(label: '当前版本', value: AppConfig.appVersion),
                    const _InfoRow(
                      label: '作者',
                      value: '小埋-XiaoMai，其他Github开发者',
                    ),
                    _InfoRow(
                      label: '服务地址',
                      value: AppConfig.hasCustomBaseUrl
                          ? AppConfig.customBaseUrl!
                          : AppConfig.apiBaseUrl,
                    ),
                    _InfoLinkRow(
                      label: 'GitHub',
                      value: 'umr-xiaomai/kgka_Music_hl',
                      onTap: () => _openRepository(context),
                    ),
                  ],
                ),
              ),
            ),
            const SliverToBoxAdapter(child: SizedBox(height: 32)),
          ],
        ),
      ),
      ),
    );
  }
}

/// 应用 Logo，使用 lib/assets/logo.png。
class _AppLogo extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      width: 108,
      height: 108,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(AppRadius.xxl),
        boxShadow: [
          BoxShadow(
            color: colorScheme.primary.withValues(alpha: .22),
            blurRadius: 24,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(AppRadius.xxl),
        child: Image.asset(
          'lib/assets/logo.png',
          fit: BoxFit.cover,
          errorBuilder: (context, error, stackTrace) {
            return Container(
              color: colorScheme.primaryContainer,
              child: Icon(
                Icons.music_note_rounded,
                size: 56,
                color: colorScheme.primary,
              ),
            );
          },
        ),
      ),
    );
  }
}

class _InfoLinkRow extends StatelessWidget {
  const _InfoLinkRow({
    required this.label,
    required this.value,
    required this.onTap,
  });

  final String label;
  final String value;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 11),
        child: Row(
          children: [
            Text(
              label,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Text(
                value,
                textAlign: TextAlign.end,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: colorScheme.primary,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              Icons.open_in_new_rounded,
              size: 18,
              color: colorScheme.primary,
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoSection extends StatelessWidget {
  const _InfoSection({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return LiquidGlassCard(
      borderRadius: AppRadius.lg,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      enableTouchFlex: false,
      child: Column(children: children),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 11),
      child: Row(
        children: [
          Text(
            label,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.end,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }
}

