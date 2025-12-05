import 'dart:async';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:ota_update/const.dart';

/// On Windows/Linux
class DesktopOtaUpdate {
  /// Execute download and installation of the plugin.
  /// Download progress and all success or error states are publish in stream as OtaEvent
  Stream<OtaEvent> execute(
    String url, {
    Map<String, String> headers = const <String, String>{},
    String? destinationFilename,
    String? sha256checksum,
  }) {
    if (destinationFilename != null && destinationFilename.contains('/')) {
      throw OtaUpdateException('Invalid filename $destinationFilename');
    }
    final StreamController<OtaEvent> controller = StreamController<OtaEvent>();
    Dio()
        .download(
          url,
          '${Directory.current.path}/$destinationFilename',
          onReceiveProgress: (int count, int total) {
            controller.add(OtaEvent(OtaStatus.DOWNLOADING, (count / total).toString()));
          },
        )
        .then((Response<dynamic> res) {
          final File f = File('${Directory.current.path}/$destinationFilename');
          final String sha256Str = sha256.convert(f.readAsBytesSync()).toString();
          if (sha256checksum != null && sha256checksum != sha256Str) {
            controller
              ..add(OtaEvent(OtaStatus.CHECKSUM_ERROR, sha256Str))
              ..close();
            return;
          }
          controller.add(OtaEvent(OtaStatus.INSTALLING, destinationFilename));
          if (destinationFilename!.endsWith('.tar.gz')) {
            print(
              'powershell taskkill /PID $pid; tar -xvf "${Directory.current.path}/$destinationFilename" ; Remove-Item -Path "${Directory.current.path}/$destinationFilename" ;start "${Platform.resolvedExecutable}"',
            );
            if (Platform.isWindows) {
              Process.run(
                'start powershell taskkill /PID $pid; tar -xvf "${Directory.current.path}/$destinationFilename" ; Remove-Item -Path "${Directory.current.path}/$destinationFilename"; start "${Platform.resolvedExecutable}";',
                <String>[],
                runInShell: true,
              );
            } else if (Platform.isLinux) {
              String script = 'kill $pid';
              script += '\ntar -xvf "${Directory.current.path}/$destinationFilename"';
              script += '\nsleep 0.1\nnohup "${Platform.resolvedExecutable}"';
              script += '\nrm -f "${Directory.current.path}/$destinationFilename""';
              script += '\nrm -f temp.sh';
              File('${Directory.current.path}/temp.sh').writeAsString(script);
              Process.start('bash', <String>['-c', 'nohup sh "${Directory.current.path}/temp.sh"']);
            }
          } else if (destinationFilename.endsWith('.zip')) {
            if (Platform.isWindows) {
              Process.run(
                'start powershell taskkill /PID $pid; Expand-Archive -Path "${Directory.current.path}/$destinationFilename"  -DestinationPath "${Directory.current.path}" -Force ; Remove-Item -Path "${Directory.current.path}/$destinationFilename"; start ${Platform.resolvedExecutable};',
                <String>[],
                runInShell: true,
              );
            } else if (Platform.isLinux) {
              String script = 'kill $pid';
              script += '\nunzip -o "${Directory.current.path}/$destinationFilename"';
              script += '\nsleep 0.1\nnohup "${Platform.resolvedExecutable}"';
              script += '\nrm -f "${Directory.current.path}/$destinationFilename""';
              script += '\nrm -f temp.sh';
              File('${Directory.current.path}/temp.sh').writeAsString(script);
              Process.start('bash', <String>['-c', 'nohup sh "${Directory.current.path}/temp.sh"']);
            }
          }
          controller.close();
        });
    return controller.stream;
  }
}
