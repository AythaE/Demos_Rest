package es.usal.tfg;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.FutureTask;

import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;

import org.apache.commons.io.FilenameUtils;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import es.usal.tfg.security.SymmetricEncryption;

public class MaintenanceService implements Runnable {

	

	@Override
	public void run() {
		
		
		System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: iniciando");
		
		
		//Borrado de la estructura de datos activeTokens
		CampaignManagement.clearActiveToken();
		
		System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: tokens activos borrados");
				
		//Se obtienen todos los FutureTask encargados de generar PDFs que haya actualmente para ir
		//cancelandoloos si no han acabado y posteriormente borrar todos los pdfs así como la 
		//estructura de datos downloadTokens
		Collection<FutureTask<File>> pdfs = CampaignManagement.getAllPDFFuture();
		
		System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: futuretask recuperadas");
		for (FutureTask<File> pdf : pdfs) {
			System.out.println(pdf.toString());
			if (!pdf.isDone()) {
				
				if(pdf.cancel(true)){
					
					System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Tarea PDF parada");
					
				}
				else{
					
					System.err.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Error parando Tarea PDF");
					
				}
			}
		}
		

		
		System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Todas las tareas PDF paradas");
		
		
		File campaingsDirectory = new File(CampaignManagement.WEBSERVICE_ABSOLUTE_ROUTE+"/campanias");
		
		try {
			borrarPDFs(campaingsDirectory);
		} catch (IOException e) {
			
			System.err.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Error borrando PDFs");
		
			e.printStackTrace();
			System.out.println(CampaignManagement.SEPARADOR);
		}
		
		System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Todos los PDF borrados");
		
		
		CampaignManagement.clearDownloadToken();
		
		System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: tokens de descarga borrados");
		

		// A continuación se lee el la base de datos de campañas comprobando que
		// no haya pasado su fecha de borrado de ser así se borra esa campaña de
		// la base de datos y su contenido, para ello se crea un fichero 
		// temporal en el que se van escribiendo las campañas validas 
		// encriptadas y se omiten las que hayan pasado de su fecha. Al finalizar
		// se copia el fichero temporal sobre el de campañas original
		
		Set<PosixFilePermission> permsRW = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
		File originalCampaignsFile = CampaignManagement.getCampaignsFile();
		File newCampaignsFile = new File(originalCampaignsFile.getParentFile(), "campaigns.tmp.json");
		CipherOutputStream cos = null;
		CipherInputStream cis = null;

		JsonReader reader = null;
		Writer wr = null;
		
		
		synchronized (CampaignManagement.lockCampaignsFile) {
			
			
			if (!CampaignManagement.campañasIsEmpty()) {
				
				try {
					Files.createFile(newCampaignsFile.toPath(), PosixFilePermissions.asFileAttribute(permsRW));
					cos = SymmetricEncryption.encryptFileUsingKey(newCampaignsFile, CampaignManagement.masterKeyAlias);
				
					cis = SymmetricEncryption.decryptFileUsingKey(originalCampaignsFile, CampaignManagement.masterKeyAlias);
					
	
				
					/**
					 * @see https://sites.google.com/site/gson/streaming
					 */
					Gson gson = new Gson();
					
					CampaignCredentials c;
					
					
					reader= new JsonReader(new InputStreamReader(cis, "UTF-8"));
					reader.setLenient(true);
					
					wr = new OutputStreamWriter(cos);
					
		
					
					while(reader.hasNext()){
						JsonToken tokenJson =  reader.peek();
						if (!tokenJson.equals(JsonToken.BEGIN_OBJECT)) {
							break;
						}
						c = gson.fromJson(reader, CampaignCredentials.class);
	
						String deleteDateStr = c.getDeleteDate();
						
						Date deleteDate = CampaignManagement.dateFormat.parse(deleteDateStr);
						
						Date fechaActual = new Date();
						
						// Si la fecha de borrado no es posterior a la actual
						// entonces se borra la campaña, en caso contrario se 
						// escribe al fichero temporal
						
						if (!deleteDate.after(fechaActual)) {
							CampaignManagement.borrarArchivosCampaña(CampaignManagement.getCampaña(c.getCampaignName()));
	
							CampaignManagement.deleteCampaña(c.getCampaignName());
							
							System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: borrada campaña "+c.getCampaignName());
	
						
						}
						else {
							gson.toJson(c, wr);
	
							System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: guardada campaña "+c.getCampaignName());
	
							
						}
					}
					
					
				
				} catch (IOException | InvalidKeyException | NoSuchAlgorithmException | KeyStoreException
						| CertificateException | NoSuchPaddingException | InvalidAlgorithmParameterException
						| UnrecoverableEntryException | ParseException e) {
					
					
					System.err.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Error comprobando fechas de campañas");
					e.printStackTrace();
					System.out.println(CampaignManagement.SEPARADOR);
				} finally {
					try {
						if(reader!= null){
							reader.close();
						}
						if (cis!=null) {
							cis.close();
						}
						
						if (wr!=null) {
							wr.flush();
							wr.close();
						}
						
						if (cos!=null) {
							cos.flush();
							cos.close();
						}
					} catch (IOException e){}
				}
				
				
				try {
					/**
					 * @see https://docs.oracle.com/javase/tutorial/essential/io/move.html
					 */
					Files.move(newCampaignsFile.toPath(), originalCampaignsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				
					System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Base de datos de campañas actualizada");
					
				} catch (IOException e) {
					
					System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Error sobreescribiendo base de datos de campañas");
					e.printStackTrace();
					System.out.println(CampaignManagement.SEPARADOR);
					
				}
					
			} else {
				System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: no existe ninguna campaña");
			}
		}
	}
	

	/**
	 * 
	 * @param c
	 * @throws IOException
	 * @reference http://stackoverflow.com/a/8685959/6441806
	 */
	private static void borrarPDFs (File campaignsDirectory) throws IOException{
		if (campaignsDirectory.isDirectory()) {
			Files.walkFileTree(campaignsDirectory.toPath(), new SimpleFileVisitor<java.nio.file.Path>()
		    {
		        @Override
		        public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs)
		                throws IOException
		        {
		        	
		        	String extension =FilenameUtils.getExtension(file.toString());
		            
		        	if (extension.equalsIgnoreCase("pdf")) {
		        		
		        		try {
							Files.delete(file);
							System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: borrado "+file.toAbsolutePath().toString());
						} catch (Exception e) {
							System.err.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Error borrando "+file.toAbsolutePath().toString());
							e.printStackTrace();
						}
					}
		        	
		            return FileVisitResult.CONTINUE;
		        }

		        @Override
		        public FileVisitResult visitFileFailed(java.nio.file.Path  file, IOException exc) throws IOException
		        {
		            // try to delete the file anyway, even if its attributes
		            // could not be read, since delete-only access is
		            // theoretically possible
		        	String extension =FilenameUtils.getExtension(file.toString());
		            
		        	if (extension.equalsIgnoreCase("pdf")) {
		        		try {
							Files.delete(file);
							System.out.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: borrado "+file.toAbsolutePath().toString());
						} catch (Exception e) {
							System.err.println("["+new Date().toString()+"] "+Thread.currentThread().getName()+" Mantenimiento: Error borrando "+file.toAbsolutePath().toString());
							e.printStackTrace();
						}
					}
		            return FileVisitResult.CONTINUE;
		        }
		        @Override
		        public FileVisitResult postVisitDirectory(java.nio.file.Path  dir, IOException exc) throws IOException
		        {
		            
		                return FileVisitResult.CONTINUE;
		            
		          
		        }
		        
		    });
		}
		 
	
	}

}
