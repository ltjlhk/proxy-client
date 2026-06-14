class TrafficStats {
  int uploadBytes;
  int downloadBytes;
  int uploadSpeed;
  int downloadSpeed;
  DateTime lastUpdated;

  TrafficStats({
    this.uploadBytes = 0,
    this.downloadBytes = 0,
    this.uploadSpeed = 0,
    this.downloadSpeed = 0,
    DateTime? lastUpdated,
  }) : lastUpdated = lastUpdated ?? DateTime.now();

  String get formattedUpload {
    return _formatBytes(uploadBytes);
  }

  String get formattedDownload {
    return _formatBytes(downloadBytes);
  }

  String get formattedUploadSpeed {
    return '${_formatBytes(uploadSpeed)}/s';
  }

  String get formattedDownloadSpeed {
    return '${_formatBytes(downloadSpeed)}/s';
  }

  String get formattedTotal {
    return _formatBytes(uploadBytes + downloadBytes);
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '${bytes}B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)}KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)}MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)}GB';
  }

  TrafficStats copyWith({
    int? uploadBytes,
    int? downloadBytes,
    int? uploadSpeed,
    int? downloadSpeed,
    DateTime? lastUpdated,
  }) {
    return TrafficStats(
      uploadBytes: uploadBytes ?? this.uploadBytes,
      downloadBytes: downloadBytes ?? this.downloadBytes,
      uploadSpeed: uploadSpeed ?? this.uploadSpeed,
      downloadSpeed: downloadSpeed ?? this.downloadSpeed,
      lastUpdated: lastUpdated ?? this.lastUpdated,
    );
  }
}
