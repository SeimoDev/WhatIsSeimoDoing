import { Module } from '@nestjs/common';
import { RetentionService } from './retention.service';
import { DatabaseModule } from '../database/database.module';

@Module({
  imports: [DatabaseModule],
  providers: [RetentionService],
})
export class RetentionModule {}
