import '../../services/cache_service.dart';
import 'package:flutter/material.dart';

import '../../controllers/auth_controller.dart';
import '../../controllers/player_controller.dart';
import '../../models/music_models.dart';
import '../../services/music_api.dart';
import '../pages/artist_detail_page.dart';
import 'toast.dart';

/// 打开歌手详情页：
/// - 单歌手：直接跳转；
/// - 多歌手：弹出底部弹窗供用户选择具体歌手；
/// - 歌曲无 artists 列表但有 artist 文本：传入名称由 ArtistDetailPage 自动搜索匹配。
Future<void> openArtistDetail({
  required BuildContext context,
  required MusicApi api,
  AuthController? auth,
  required PlayerController player,
  required Song song,
  ArtistRef? specificArtist,
}) async {
  if (specificArtist != null) {
    if (!context.mounted) return;
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ArtistDetailPage(
          api: api,
          auth: auth ?? AuthController(api, CacheService()),
          artist: specificArtist,
          player: player,
        ),
      ),
    );
    return;
  }

  final artists = List<ArtistRef>.from(song.artists);
  if (artists.isEmpty) {
    final rawArtist = song.artist.trim();
    if (rawArtist.isEmpty || rawArtist == '未知艺人' || rawArtist == '未知歌手') {
      Toast.info('暂无歌手信息');
      return;
    }

    // 尝试根据常见分隔符拆分多歌手文本
    final splitNames = rawArtist
        .split(RegExp(r'\s*[/、&,，]\s*'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();

    if (splitNames.length > 1) {
      for (final name in splitNames) {
        artists.add(ArtistRef(id: '', name: name));
      }
    } else {
      artists.add(ArtistRef(id: '', name: rawArtist));
    }
  }

  ArtistRef? selected;
  if (artists.length == 1) {
    selected = artists.first;
  } else if (artists.length > 1) {
    selected = await showModalBottomSheet<ArtistRef>(
      context: context,
      showDragHandle: true,
      backgroundColor: Theme.of(context).colorScheme.surface,
      builder: (sheetContext) {
        return SafeArea(
          child: ListView.separated(
            shrinkWrap: true,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            itemCount: artists.length,
            separatorBuilder: (_, _) => const SizedBox(height: 4),
            itemBuilder: (context, index) {
              final artist = artists[index];
              return ListTile(
                leading: CircleAvatar(
                  backgroundImage: artist.avatarUrl == null
                      ? null
                      : NetworkImage(artist.avatarUrl!),
                  child: artist.avatarUrl == null
                      ? const Icon(Icons.person_rounded)
                      : null,
                ),
                title: Text(
                  artist.name,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                onTap: () => Navigator.of(sheetContext).pop(artist),
              );
            },
          ),
        );
      },
    );
  }

  if (selected == null || !context.mounted) return;
  Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => ArtistDetailPage(
        api: api,
        auth: auth ?? AuthController(api, CacheService()),
        artist: selected!,
        player: player,
      ),
    ),
  );
}

/// 可点击的歌手文本组件。
///
/// 独立消费点击事件，点击后直接打开歌手详情页（或多歌手选择弹窗），
/// 避免误触所在行或卡片的整行播放逻辑。
class ClickableArtistText extends StatelessWidget {
  const ClickableArtistText({
    super.key,
    required this.song,
    this.onTap,
    this.api,
    this.auth,
    this.player,
    this.style,
    this.maxLines = 1,
    this.overflow = TextOverflow.ellipsis,
    this.padding,
  });

  final Song song;
  final VoidCallback? onTap;
  final MusicApi? api;
  final AuthController? auth;
  final PlayerController? player;
  final TextStyle? style;
  final int? maxLines;
  final TextOverflow overflow;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    final displayText = song.artist.isEmpty ? '未知艺人' : song.artist;

    Widget child = Text(
      displayText,
      maxLines: maxLines,
      overflow: overflow,
      style: style,
    );

    if (padding != null) {
      child = Padding(padding: padding!, child: child);
    }

    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {
        if (onTap != null) {
          onTap!();
        } else if (api != null && auth != null && player != null) {
          openArtistDetail(
            context: context,
            api: api!,
            auth: auth!,
            player: player!,
            song: song,
          );
        }
      },
      child: child,
    );
  }
}
