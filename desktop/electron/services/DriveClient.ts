import { google, drive_v3 } from 'googleapis';
import { Readable } from 'stream';

const FOLDER_MIME = 'application/vnd.google-apps.folder';

export class DriveClient {
  private drive: drive_v3.Drive;

  constructor(accessToken: string) {
    const auth = new google.auth.OAuth2();
    auth.setCredentials({ access_token: accessToken });
    this.drive = google.drive({ version: 'v3', auth });
  }

  /** Find a folder by name under a parent. Returns id or null. */
  async findFolder(name: string, parentId: string | 'root' = 'root'): Promise<string | null> {
    const escaped = name.replace(/'/g, "\\'");
    const q = `mimeType='${FOLDER_MIME}' and name='${escaped}' and trashed=false and '${parentId}' in parents`;
    const res = await this.drive.files.list({
      q,
      fields: 'files(id,name)',
      pageSize: 1,
    });
    return res.data.files?.[0]?.id ?? null;
  }

  async createFolder(name: string, parentId: string | 'root' = 'root'): Promise<string> {
    const res = await this.drive.files.create({
      requestBody: {
        name,
        mimeType: FOLDER_MIME,
        parents: [parentId],
      },
      fields: 'id',
    });
    if (!res.data.id) throw new Error('Drive folder creation returned no id');
    return res.data.id;
  }

  async ensureFolder(name: string, parentId: string | 'root' = 'root'): Promise<string> {
    const existing = await this.findFolder(name, parentId);
    if (existing) return existing;
    return this.createFolder(name, parentId);
  }

  /** Find a non-folder file by name within a parent folder. */
  async findFile(name: string, parentId: string): Promise<string | null> {
    const escaped = name.replace(/'/g, "\\'");
    const q = `name='${escaped}' and trashed=false and '${parentId}' in parents and mimeType!='${FOLDER_MIME}'`;
    const res = await this.drive.files.list({
      q,
      fields: 'files(id,name,modifiedTime,size)',
      pageSize: 1,
    });
    return res.data.files?.[0]?.id ?? null;
  }

  /** Upload data, replacing the file in place if it already exists. */
  async upsertFile(
    name: string,
    parentId: string,
    body: Buffer | Readable,
    mimeType = 'application/octet-stream'
  ): Promise<string> {
    const existingId = await this.findFile(name, parentId);
    const media = {
      mimeType,
      body: body instanceof Buffer ? Readable.from(body) : body,
    };
    if (existingId) {
      const res = await this.drive.files.update({
        fileId: existingId,
        media,
        fields: 'id',
      });
      if (!res.data.id) throw new Error('Drive update returned no id');
      return res.data.id;
    }
    const res = await this.drive.files.create({
      requestBody: { name, parents: [parentId] },
      media,
      fields: 'id',
    });
    if (!res.data.id) throw new Error('Drive create returned no id');
    return res.data.id;
  }

  async downloadFile(fileId: string): Promise<Buffer> {
    const res = await this.drive.files.get(
      { fileId, alt: 'media' },
      { responseType: 'arraybuffer' }
    );
    return Buffer.from(res.data as ArrayBuffer);
  }

  async listChildren(parentId: string): Promise<Array<{ id: string; name: string; modifiedTime?: string; mimeType: string }>> {
    const res = await this.drive.files.list({
      q: `'${parentId}' in parents and trashed=false`,
      fields: 'files(id,name,modifiedTime,mimeType)',
      pageSize: 200,
    });
    return (res.data.files ?? []).map((f) => ({
      id: f.id ?? '',
      name: f.name ?? '',
      modifiedTime: f.modifiedTime ?? undefined,
      mimeType: f.mimeType ?? '',
    }));
  }

  async deleteFile(fileId: string): Promise<void> {
    await this.drive.files.delete({ fileId });
  }
}
